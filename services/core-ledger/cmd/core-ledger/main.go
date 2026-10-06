// Command core-ledger serves the double-entry posting API.
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"

	"github.com/fortress-upi/fortress/services/core-ledger/internal/adapters/httpapi"
	"github.com/fortress-upi/fortress/services/core-ledger/internal/adapters/memjournal"
	"github.com/fortress-upi/fortress/services/core-ledger/internal/app"
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
	log := server.NewLogger("core-ledger").With("version", version, "commit", commit)
	if err := run(addr, log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(addr string, log *slog.Logger) error {
	if os.Getenv("FORTRESS_ENV") == "production" {
		return errors.New("in-memory journal cannot run in production; link the crdb adapter")
	}
	svc := app.NewPostService(memjournal.New(10_000_000))

	reg := metrics.NewRegistry()
	api := http.NewServeMux()
	httpapi.Routes(api, svc, log)

	root := http.NewServeMux()
	root.Handle("GET /metrics", reg.Handler())
	root.Handle("/v1/", reg.Instrument("journals", api))
	return server.Run(context.Background(), server.Config{Addr: addr}, root, log)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}
