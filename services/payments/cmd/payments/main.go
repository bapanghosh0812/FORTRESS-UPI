// Command payments orchestrates online UPI payments through the sponsor PSP.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/fortress-upi/fortress/services/payments/internal/adapters/httpapi"
	"github.com/fortress-upi/fortress/services/payments/internal/adapters/memstore"
	"github.com/fortress-upi/fortress/services/payments/internal/adapters/sandboxpsp"
	"github.com/fortress-upi/fortress/services/payments/internal/app"
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
	log := server.NewLogger("payments").With("version", version, "commit", commit)
	if err := run(addr, log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(addr string, log *slog.Logger) error {
	if os.Getenv("FORTRESS_ENV") == "production" || envOr("PSP_MODE", "sandbox") != "sandbox" {
		return errors.New("payments: only the sandbox PSP and memory store are linked in this build")
	}
	secret, err := readSecret(os.Getenv("PSP_HMAC_SECRET_FILE"))
	if err != nil {
		return err
	}
	limits, err := limitsFromEnv()
	if err != nil {
		return err
	}
	psp := sandboxpsp.New(time.Now, 900*time.Millisecond)
	svc := app.New(memstore.New(), psp, psp, time.Now, app.Config{MaxPaise: limits.PerTxnUPIPaise}, log)
	dir, err := app.NewDirectoryService(psp, limits)
	if err != nil {
		return err
	}

	reg := metrics.NewRegistry()
	api := http.NewServeMux()
	httpapi.Routes(api, svc, secret, log)
	httpapi.DirectoryRoutes(api, dir, log)
	root := http.NewServeMux()
	root.Handle("GET /metrics", reg.Handler())
	root.Handle("/", reg.Instrument("payments", api))
	return server.Run(context.Background(), server.Config{Addr: addr}, root, log)
}

func readSecret(path string) ([]byte, error) {
	if path == "" {
		return nil, errors.New("PSP_HMAC_SECRET_FILE is required")
	}
	b, err := os.ReadFile(path) //nolint:gosec // operator-supplied path
	if err != nil {
		return nil, fmt.Errorf("read psp secret: %w", err)
	}
	if s := strings.TrimSpace(string(b)); len(s) >= 32 {
		return []byte(s), nil
	}
	return nil, errors.New("PSP HMAC secret must be at least 32 characters")
}

// limitsFromEnv reads the published payment policy. Each value defaults to the
// cap in force at build time; operators override them when a circular changes.
func limitsFromEnv() (app.Limits, error) {
	l := app.DefaultLimits()
	for _, f := range []struct {
		key string
		dst *uint64
	}{
		{"UPI_PER_TXN_PAISE", &l.PerTxnUPIPaise},
		{"UPI_DAILY_PAISE", &l.DailyUPIPaise},
		{"OFFLINE_PER_TXN_PAISE", &l.OfflinePerTxnPaise},
		{"OFFLINE_WALLET_PAISE", &l.OfflineWalletPaise},
		{"NEW_DEVICE_CAP_PAISE", &l.NewDeviceCapPaise},
	} {
		if v := os.Getenv(f.key); v != "" {
			n, err := strconv.ParseUint(v, 10, 64)
			if err != nil {
				return l, fmt.Errorf("%s: %w", f.key, err)
			}
			*f.dst = n
		}
	}
	if v := os.Getenv("UPI_DAILY_COUNT"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			return l, fmt.Errorf("UPI_DAILY_COUNT: %w", err)
		}
		l.DailyCount = n
	}
	l.Source = envOr("LIMITS_SOURCE", l.Source)
	return l, l.Validate()
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}
