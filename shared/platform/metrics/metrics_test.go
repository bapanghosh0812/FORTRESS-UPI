package metrics

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestInstrumentAndExposition(t *testing.T) {
	r := NewRegistry()
	ok := r.Instrument("ok", http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	bad := r.Instrument("bad", http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusConflict)
	}))
	for range 3 {
		ok.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))
	}
	bad.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/", nil))

	w := httptest.NewRecorder()
	r.Handler().ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	body := w.Body.String()
	for _, want := range []string{
		`fortress_http_requests_total{route="ok",code="2xx"} 3`,
		`fortress_http_requests_total{route="bad",code="4xx"} 1`,
		`fortress_http_request_duration_seconds_bucket{route="ok",le="+Inf"} 3`,
		`fortress_http_request_duration_seconds_count{route="bad"} 1`,
		`le="0.015"`,
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in:\n%s", want, body)
		}
	}
}
