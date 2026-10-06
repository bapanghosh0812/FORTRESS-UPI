// Command offline-mesh is the composition root of the offline settlement
// engine: it reads configuration, wires adapters into the use case and serves.
package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/adapters/httpapi"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/adapters/ledger"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/adapters/memstore"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/app"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/attest"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
	"github.com/fortress-upi/fortress/shared/crypto/signer"
	"github.com/fortress-upi/fortress/shared/platform/metrics"
	"github.com/fortress-upi/fortress/shared/platform/server"
)

// Injected at build time via -ldflags.
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

	log := server.NewLogger("offline-mesh").With("version", version, "commit", commit)
	if err := run(addr, log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(addr string, log *slog.Logger) error {
	keys, err := parseIssuerKeys(os.Getenv("ISSUER_KEYS"))
	if err != nil {
		return err
	}
	grace, err := time.ParseDuration(envOr("SETTLEMENT_GRACE", "168h"))
	if err != nil {
		return fmt.Errorf("invalid SETTLEMENT_GRACE: %w", err)
	}
	// The RBI offline vault cap (₹5,000 under the rules in force at build time).
	walletCap, err := envPaise("OFFLINE_WALLET_PAISE", 500_000)
	if err != nil {
		return err
	}

	storeKind := envOr("STORE", "memory")
	if storeKind == "memory" && os.Getenv("FORTRESS_ENV") == "production" {
		return errors.New("refusing to run the in-memory store in production; configure STORE=crdb")
	}
	if storeKind != "memory" {
		return fmt.Errorf("store adapter %q is not linked in this build", storeKind)
	}
	store := memstore.New(5_000_000)

	svc := app.NewSettleService(keys, store, &memstore.LogFraud{}, time.Now, app.Config{
		SettlementGrace:  grace,
		MinEnforcement:   domain.EnforcementTEE,
		MaxBundleTotal:   walletCap, // paise; the RBI offline vault cap, same value the issuer enforces
		AllowRedemptions: true,
	}, log)

	reg := metrics.NewRegistry()
	api := http.NewServeMux()
	httpapi.Routes(api, svc, log)

	// Vault loading (issuance) is enabled when an issuer signing key is configured.
	if path := os.Getenv("ISSUER_KEY_FILE"); path != "" {
		issueSvc, err := newIssuance(path, walletCap, log)
		if err != nil {
			return err
		}
		httpapi.IssuanceRoutes(api, issueSvc, log)
	}

	root := http.NewServeMux()
	root.Handle("GET /metrics", reg.Handler())
	root.Handle("/v1/offline/", reg.Instrument("offline_settlements", api))

	return server.Run(context.Background(), server.Config{Addr: addr}, root, log)
}

func newIssuance(keyPath string, walletCap uint64, log *slog.Logger) (*app.IssueService, error) {
	id, err := hex.DecodeString(os.Getenv("ISSUER_KEY_ID"))
	if err != nil || len(id) == 0 {
		return nil, errors.New("ISSUER_KEY_ID must be hex when ISSUER_KEY_FILE is set")
	}
	issuer, err := signer.LoadSoftware(keyPath, id)
	if err != nil {
		return nil, err
	}
	spki, err := sig.MarshalPublicKey(issuer.Public())
	if err != nil {
		return nil, err
	}
	// Paste into the app's gradle property fortress.issuerKeys for development builds.
	log.Info("issuer public key", "fortress.issuerKeys", hex.EncodeToString(id)+":"+base64.StdEncoding.EncodeToString(spki))

	roots, err := attest.LoadRootsPEM(os.Getenv("ATTESTATION_ROOTS_FILE"))
	if err != nil {
		return nil, err
	}
	digests, err := attest.ParseDigests(os.Getenv("APP_SIGNER_SHA256"))
	if err != nil {
		return nil, err
	}
	var led app.Ledger = ledger.NewMemory()
	if url := os.Getenv("CORE_LEDGER_URL"); url != "" {
		led = ledger.NewHTTP(url)
	}
	return app.NewIssueService(issuer, led, time.Now, app.IssueConfig{
		Attestation: attest.Policy{
			Roots: roots, PackageName: envOr("APP_PACKAGE", "com.fortress.upi"), SignerDigests: digests,
			MinLevel: attest.LevelTEE, RequireLockedVerifiedBoot: true,
		},
		NoteTTL:       72 * time.Hour,
		ChallengeTTL:  5 * time.Minute,
		MaxVaultPaise: walletCap, // OFFLINE_WALLET_PAISE: set from the current RBI/NPCI offline circular
		Denominations: map[uint64]bool{1_000: true, 2_000: true, 5_000: true, 10_000: true, 20_000: true, 50_000: true},
	}, log), nil
}

// parseIssuerKeys parses "kid:base64(spki);kid2:base64(spki)". Only public
// keys are configured here; issuer private keys never leave the HSM.
func parseIssuerKeys(spec string) (domain.StaticIssuerKeys, error) {
	keys := domain.StaticIssuerKeys{}
	for _, entry := range strings.Split(spec, ";") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		kid, b64, ok := strings.Cut(entry, ":")
		if !ok {
			return nil, errors.New("invalid ISSUER_KEYS entry: expected kid:base64spki")
		}
		if _, err := hex.DecodeString(kid); err != nil || len(kid) == 0 || len(kid) > 64 {
			return nil, errors.New("invalid ISSUER_KEYS entry: key id must be 1-32 bytes of hex")
		}
		der, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			return nil, fmt.Errorf("invalid ISSUER_KEYS entry: %w", err)
		}
		pub, err := sig.ParseP256PublicKey(der)
		if err != nil {
			return nil, fmt.Errorf("invalid ISSUER_KEYS key: %w", err)
		}
		keys[strings.ToLower(kid)] = pub
	}
	if len(keys) == 0 {
		return nil, errors.New("no issuer keys configured (ISSUER_KEYS)")
	}
	return keys, nil
}

// envPaise reads a positive paise amount, falling back to def when unset.
func envPaise(k string, def uint64) (uint64, error) {
	v := os.Getenv(k)
	if v == "" {
		return def, nil
	}
	n, err := strconv.ParseUint(v, 10, 64)
	if err != nil || n == 0 {
		return 0, fmt.Errorf("%s must be a positive paise amount, got %q", k, v)
	}
	return n, nil
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}
