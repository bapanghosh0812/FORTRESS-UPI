// Command security-gateway verifies device signatures and routes verified
// requests to mesh upstreams (identity, payments, offline-mesh).
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"time"

	"github.com/fortress-upi/fortress/services/security-gateway/internal/adapters/httpregistry"
	"github.com/fortress-upi/fortress/services/security-gateway/internal/adapters/memnonce"
	"github.com/fortress-upi/fortress/services/security-gateway/internal/adapters/memregistry"
	"github.com/fortress-upi/fortress/services/security-gateway/internal/devicesig"
	"github.com/fortress-upi/fortress/services/security-gateway/internal/routing"
	"github.com/fortress-upi/fortress/shared/platform/metrics"
	"github.com/fortress-upi/fortress/shared/platform/server"
)

var (
	version = "dev"
	commit  = "unknown"
)

func main() {
	healthcheck := flag.Bool("healthcheck", false, "probe the local /healthz endpoint and exit")
	flag.Parse()
	addr := envOr("ADDR", ":8080")
	if *healthcheck {
		os.Exit(server.Probe(addr))
	}
	log := server.NewLogger("security-gateway").With("version", version, "commit", commit)
	if err := run(addr, log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(addr string, log *slog.Logger) error {
	if os.Getenv("FORTRESS_ENV") == "production" {
		return errors.New("security-gateway: the in-memory nonce store is single-replica only; link the Redis adapter for production")
	}
	routes, err := routing.Parse(envOr("ROUTES", "/v1/offline/|"+os.Getenv("UPSTREAM_URL")))
	if err != nil {
		return err
	}

	var registry devicesig.Registry
	if base := os.Getenv("DEVICE_REGISTRY_URL"); base != "" {
		registry = httpregistry.New(base, 5*time.Second)
	} else {
		static, err := memregistry.Parse(os.Getenv("DEVICE_KEYS"))
		if err != nil {
			return err
		}
		registry = static
	}
	verifier := devicesig.NewVerifier(registry, memnonce.New(1_000_000, time.Now), time.Now, log)

	transport := &http.Transport{
		DialContext:           (&net.Dialer{Timeout: time.Second, KeepAlive: 30 * time.Second}).DialContext,
		MaxIdleConns:          1024,
		MaxIdleConnsPerHost:   512,
		IdleConnTimeout:       90 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second, // binding long-polls for the SMS
		ForceAttemptHTTP2:     true,
	}
	proxies := map[string]http.Handler{}
	for _, r := range routes {
		if _, ok := proxies[r.Upstream.String()]; !ok {
			proxies[r.Upstream.String()] = newProxy(r.Upstream, transport, log)
		}
	}

	dispatch := http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		route, ok := routing.Match(routes, req.URL.Path)
		if !ok {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"not_found"}`))
			return
		}
		proxy := proxies[route.Upstream.String()]
		if route.Public {
			req.Header.Del(devicesig.HeaderVerified) // never trust a client-asserted identity
			proxy.ServeHTTP(w, req)
			return
		}
		verifier.Middleware(proxy).ServeHTTP(w, req)
	})

	reg := metrics.NewRegistry()
	root := http.NewServeMux()
	root.Handle("GET /metrics", reg.Handler())
	root.Handle("/v1/", reg.Instrument("verified_proxy", dispatch))
	return server.Run(context.Background(), server.Config{Addr: addr, WriteTimeout: 35 * time.Second}, root, log)
}

func newProxy(target *url.URL, transport http.RoundTripper, log *slog.Logger) http.Handler {
	return &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(target)
			pr.SetXForwarded()
		},
		Transport: transport,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			log.WarnContext(r.Context(), "upstream error", "upstream", target.Host, "err", err)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadGateway)
			_, _ = w.Write([]byte(`{"error":"upstream_unavailable"}`))
		},
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}
