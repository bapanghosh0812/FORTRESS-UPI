package server

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

var discard = slog.New(slog.NewTextHandler(io.Discard, nil))

func TestHardenSetsHeadersAndContainsPanics(t *testing.T) {
	h := Harden(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		panic("secret internal detail")
	}), discard)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/", nil))
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("code = %d", w.Code)
	}
	if strings.Contains(w.Body.String(), "secret") {
		t.Fatal("panic detail leaked to client")
	}
	for _, hdr := range []string{"Cache-Control", "X-Content-Type-Options", "Content-Security-Policy", "Referrer-Policy"} {
		if w.Header().Get(hdr) == "" {
			t.Fatalf("missing %s", hdr)
		}
	}
}

func TestServeProbesAndGracefulShutdown(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	app := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusTeapot) })
	go func() {
		done <- Serve(ctx, ln, Config{DrainDelay: time.Millisecond, ShutdownGrace: time.Second}, app, discard)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for Probe(addr) != 0 {
		if time.Now().After(deadline) {
			t.Fatal("server never became healthy")
		}
		time.Sleep(10 * time.Millisecond)
	}
	resp, err := http.Get("http://" + addr + "/readyz") //nolint:noctx // test
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("readyz: %v %v", resp, err)
	}
	_ = resp.Body.Close()
	resp, err = http.Get("http://" + addr + "/anything") //nolint:noctx // test
	if err != nil || resp.StatusCode != http.StatusTeapot {
		t.Fatalf("app route: %v %v", resp, err)
	}
	_ = resp.Body.Close()

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("shutdown: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown hung")
	}
	if Probe(addr) == 0 {
		t.Fatal("server still answering after shutdown")
	}
}

func TestDefaultsAndLogger(t *testing.T) {
	c := Config{}.withDefaults()
	if c.Addr != ":8080" || c.ReadHeaderTimeout == 0 || c.MaxHeaderBytes == 0 || c.DrainDelay == 0 {
		t.Fatalf("defaults not applied: %+v", c)
	}
	if NewLogger("svc") == nil {
		t.Fatal("nil logger")
	}
	if Probe("127.0.0.1:1") == 0 {
		t.Fatal("probe of closed port succeeded")
	}
}
