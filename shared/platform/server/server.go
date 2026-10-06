// Package server is the single hardened HTTP bootstrap shared by every Fortress
// service: strict timeouts, bounded headers, panic containment, security
// headers, health probes and graceful shutdown on SIGTERM.
package server

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

// Config holds listener settings. Zero values are replaced by safe defaults.
type Config struct {
	Addr              string
	ReadHeaderTimeout time.Duration
	ReadTimeout       time.Duration
	WriteTimeout      time.Duration
	IdleTimeout       time.Duration
	ShutdownGrace     time.Duration
	// DrainDelay is how long readiness reports failure before connections
	// close, so load balancers stop routing first. Default 3 s.
	DrainDelay     time.Duration
	MaxHeaderBytes int
}

func (c Config) withDefaults() Config {
	if c.Addr == "" {
		c.Addr = ":8080"
	}
	if c.ReadHeaderTimeout == 0 {
		c.ReadHeaderTimeout = 2 * time.Second
	}
	if c.ReadTimeout == 0 {
		c.ReadTimeout = 5 * time.Second
	}
	if c.WriteTimeout == 0 {
		c.WriteTimeout = 5 * time.Second
	}
	if c.IdleTimeout == 0 {
		c.IdleTimeout = 90 * time.Second
	}
	if c.ShutdownGrace == 0 {
		c.ShutdownGrace = 20 * time.Second
	}
	if c.DrainDelay == 0 {
		c.DrainDelay = 3 * time.Second
	}
	if c.MaxHeaderBytes == 0 {
		c.MaxHeaderBytes = 16 << 10
	}
	return c
}

// Run listens on cfg.Addr and serves until SIGINT/SIGTERM or ctx cancellation.
func Run(ctx context.Context, cfg Config, app http.Handler, log *slog.Logger) error {
	cfg = cfg.withDefaults()
	ln, err := (&net.ListenConfig{}).Listen(ctx, "tcp", cfg.Addr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", cfg.Addr, err)
	}
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()
	return Serve(ctx, ln, cfg, app, log)
}

// Serve serves on ln until ctx is cancelled, then drains in-flight requests.
// Readiness flips to failing first so the load balancer stops routing before
// connections close.
func Serve(ctx context.Context, ln net.Listener, cfg Config, app http.Handler, log *slog.Logger) error {
	cfg = cfg.withDefaults()
	var ready atomic.Bool

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, _ *http.Request) {
		if !ready.Load() {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})
	mux.Handle("/", app)

	srv := &http.Server{
		Handler:           Harden(mux, log),
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		ReadTimeout:       cfg.ReadTimeout,
		WriteTimeout:      cfg.WriteTimeout,
		IdleTimeout:       cfg.IdleTimeout,
		MaxHeaderBytes:    cfg.MaxHeaderBytes,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}

	errCh := make(chan error, 1)
	go func() { errCh <- srv.Serve(ln) }()
	ready.Store(true)
	log.Info("server started", "addr", ln.Addr().String())

	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
	}

	ready.Store(false)
	log.Info("shutdown requested; draining")
	time.Sleep(cfg.DrainDelay)
	// Keep request-scoped values but not the cancellation that triggered shutdown.
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), cfg.ShutdownGrace)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}

// Harden wraps h with panic containment and API security headers.
func Harden(h http.Handler, log *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hdr := w.Header()
		hdr.Set("Cache-Control", "no-store")
		hdr.Set("X-Content-Type-Options", "nosniff")
		hdr.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		hdr.Set("Referrer-Policy", "no-referrer")
		defer func() {
			if rec := recover(); rec != nil {
				if rec == http.ErrAbortHandler { //nolint:errorlint // sentinel comparison is the documented contract
					panic(rec)
				}
				// Never echo panic details to clients.
				log.Error("panic recovered", "path", r.URL.Path, "panic", fmt.Sprint(rec))
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusInternalServerError)
				_, _ = w.Write([]byte(`{"error":"internal"}`))
			}
		}()
		h.ServeHTTP(w, r)
	})
}

// Probe performs a local health check and returns a process exit code. It is
// used by `HEALTHCHECK CMD ["/app/service","-healthcheck"]` because distroless
// images have no shell or curl.
func Probe(addr string) int {
	host := addr
	if strings.HasPrefix(host, ":") {
		host = "127.0.0.1" + host
	}
	client := &http.Client{Timeout: 2 * time.Second}
	req, err := http.NewRequestWithContext(context.Background(), http.MethodGet, "http://"+host+"/healthz", nil)
	if err != nil {
		return 1
	}
	resp, err := client.Do(req)
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

// NewLogger returns a JSON structured logger. Services must never log secrets,
// full request bodies, VPAs, account numbers or device keys.
func NewLogger(service string) *slog.Logger {
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})).
		With("service", service)
}
