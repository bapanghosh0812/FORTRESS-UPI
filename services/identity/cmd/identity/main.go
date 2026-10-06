// Command identity binds devices: attestation + SMS SIM proof → receiver certificate.
package main

import (
	"context"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/fortress-upi/fortress/services/identity/internal/adapters/httpapi"
	"github.com/fortress-upi/fortress/services/identity/internal/adapters/memstore"
	"github.com/fortress-upi/fortress/services/identity/internal/app"
	"github.com/fortress-upi/fortress/shared/crypto/attest"
	"github.com/fortress-upi/fortress/shared/crypto/signer"
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
	log := server.NewLogger("identity").With("version", version, "commit", commit)
	if err := run(addr, log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(addr string, log *slog.Logger) error {
	production := os.Getenv("FORTRESS_ENV") == "production"
	if production {
		// The PKCS#11 issuer signer, CockroachDB store and Play Integrity adapter
		// are linked in the production build; this binary has dev adapters only.
		return errors.New("identity: dev adapters (memory store, software issuer, dev integrity) cannot run in production")
	}

	roots, err := attest.LoadRootsPEM(os.Getenv("ATTESTATION_ROOTS_FILE"))
	if err != nil {
		return err
	}
	digests, err := attest.ParseDigests(os.Getenv("APP_SIGNER_SHA256"))
	if err != nil {
		return err
	}
	issuer, err := loadIssuer()
	if err != nil {
		return err
	}
	secret, err := readSecret(os.Getenv("SMS_HMAC_SECRET_FILE"))
	if err != nil {
		return err
	}

	svc := app.New(memstore.New(), devIntegrity{log: log}, issuer, time.Now, app.Config{
		VirtualNumber: envOr("VIRTUAL_NUMBER", "+919000000000"),
		SessionTTL:    10 * time.Minute,
		SMSWait:       25 * time.Second,
		CertTTL:       365 * 24 * time.Hour,
		MaxClockSkew:  5 * time.Minute,
		Attestation: attest.Policy{
			Roots: roots, PackageName: envOr("APP_PACKAGE", "com.fortress.upi"), SignerDigests: digests,
			MinLevel: attest.LevelTEE, RequireLockedVerifiedBoot: true,
		},
	}, log)

	reg := metrics.NewRegistry()
	api := http.NewServeMux()
	httpapi.Routes(api, svc, secret, log)
	root := http.NewServeMux()
	root.Handle("GET /metrics", reg.Handler())
	root.Handle("/", reg.Instrument("identity", api))
	// Binding long-polls for the SMS, so writes may take up to ~30 s.
	return server.Run(context.Background(), server.Config{Addr: addr, WriteTimeout: 35 * time.Second}, root, log)
}

// devIntegrity accepts any token outside production and logs that it did.
type devIntegrity struct{ log *slog.Logger }

func (d devIntegrity) Verify(ctx context.Context, token string, _ [32]byte) error {
	if token == "" {
		return errors.New("empty integrity token")
	}
	d.log.WarnContext(ctx, "play integrity NOT verified (dev adapter)")
	return nil
}

func loadIssuer() (*signer.Software, error) {
	id, err := hex.DecodeString(os.Getenv("ISSUER_KEY_ID"))
	if err != nil || len(id) == 0 {
		return nil, errors.New("ISSUER_KEY_ID must be hex")
	}
	return signer.LoadSoftware(os.Getenv("ISSUER_KEY_FILE"), id)
}

func readSecret(path string) ([]byte, error) {
	if path == "" {
		return nil, errors.New("SMS_HMAC_SECRET_FILE is required")
	}
	b, err := os.ReadFile(path) //nolint:gosec // operator-supplied path
	if err != nil {
		return nil, fmt.Errorf("read sms secret: %w", err)
	}
	if s := strings.TrimSpace(string(b)); len(s) >= 32 {
		return []byte(s), nil
	}
	return nil, errors.New("SMS HMAC secret must be at least 32 characters")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}
