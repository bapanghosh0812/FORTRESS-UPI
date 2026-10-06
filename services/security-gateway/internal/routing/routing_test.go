package routing

import "testing"

func TestParseAndLongestPrefixMatch(t *testing.T) {
	routes, err := Parse("/v1/|http://payments:8080; /v1/bind/|http://identity:8080|public ;/v1/offline/|http://offline-mesh:8080")
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"/v1/bind/begin":           "identity:8080",
		"/v1/offline/settlements":  "offline-mesh:8080",
		"/v1/payments/pay_1":       "payments:8080",
		"/v1/accounts":             "payments:8080",
	}
	for path, host := range cases {
		r, ok := Match(routes, path)
		if !ok || r.Upstream.Host != host {
			t.Fatalf("%s → %v (ok=%v)", path, r.Upstream, ok)
		}
	}
	if r, _ := Match(routes, "/v1/bind/complete"); !r.Public {
		t.Fatal("binding route must be public")
	}
	if r, _ := Match(routes, "/v1/payments"); r.Public {
		t.Fatal("payments must require device signatures")
	}
	if _, ok := Match(routes, "/internal/devices/x"); ok {
		t.Fatal("internal paths must never be routable")
	}
}

func TestParseRejectsBadSpecs(t *testing.T) {
	for _, spec := range []string{"", "/v1/x|ftp://h", "/admin/|http://h:1", "/v1/x|http://h:1|private"} {
		if _, err := Parse(spec); err == nil {
			t.Fatalf("accepted %q", spec)
		}
	}
}
