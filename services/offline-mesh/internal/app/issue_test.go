package app_test

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/adapters/ledger"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/app"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/attest"
	"github.com/fortress-upi/fortress/shared/crypto/attest/attesttest"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
	"github.com/fortress-upi/fortress/shared/crypto/signer"
)

var appDigest = sha256.Sum256([]byte("fortress-release-cert"))

type issueRig struct {
	svc    *app.IssueService
	ca     *attesttest.CA
	issuer *signer.Software
	ledger *ledger.Memory
}

func newIssueRig(t *testing.T) *issueRig {
	t.Helper()
	ca, err := attesttest.NewCA()
	if err != nil {
		t.Fatal(err)
	}
	issuer, err := signer.Generate([]byte{0x01, 0x02})
	if err != nil {
		t.Fatal(err)
	}
	led := ledger.NewMemory()
	now := time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)
	svc := app.NewIssueService(issuer, led, func() time.Time { return now }, app.IssueConfig{
		Attestation: attest.Policy{
			Roots: [][]byte{ca.RootSPKI()}, PackageName: "com.fortress.upi", SignerDigests: [][]byte{appDigest[:]},
			MinLevel: attest.LevelTEE, RequireLockedVerifiedBoot: true,
		},
		NoteTTL: 72 * time.Hour, ChallengeTTL: 5 * time.Minute, MaxVaultPaise: 500_000,
		Denominations: map[uint64]bool{1_000: true, 2_000: true, 5_000: true, 10_000: true, 20_000: true, 50_000: true},
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return &issueRig{svc: svc, ca: ca, issuer: issuer, ledger: led}
}

func (r *issueRig) notes(t *testing.T, challenge [32]byte, usage int, denoms ...uint64) []app.NoteRequest {
	t.Helper()
	out := make([]app.NoteRequest, 0, len(denoms))
	for i, d := range denoms {
		k, _ := sig.GenerateP256()
		spki, _ := sig.MarshalPublicKey(&k.PublicKey)
		chain, err := r.ca.Attest(&k.PublicKey, challenge[:], attesttest.Options{Level: 2, UsageLimit: usage, Package: "com.fortress.upi", SignerDigest: appDigest[:]})
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, app.NoteRequest{KeyAlias: fmt.Sprintf("fortress.note.%032x", i+1), DenominationPaise: d, PublicKey: spki, AttestationChain: chain})
	}
	return out
}

func TestIssueCertifiesHardwareSingleUseKeysAndDebitsOnce(t *testing.T) {
	r, ctx := newIssueRig(t), context.Background()
	ch, err := r.svc.Challenge(ctx, "dev_a")
	if err != nil {
		t.Fatal(err)
	}
	req := r.notes(t, ch, 1, 50_000, 20_000, 5_000)
	issued, err := r.svc.Issue(ctx, "dev_a", ch, "acc_fortress_4402", req)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	keys := domain.StaticIssuerKeys{fmt.Sprintf("%x", r.issuer.KeyID()): r.issuer.Public()}
	for _, n := range issued {
		cert, err := domain.ParseSignedNoteCert(n.Certificate)
		if err != nil {
			t.Fatal(err)
		}
		if err := cert.VerifyIssuer(keys); err != nil {
			t.Fatalf("issuer signature: %v", err)
		}
		if cert.Cert.Enforcement != domain.EnforcementStrongBox || cert.Cert.WalletID != app.WalletID("dev_a") {
			t.Fatalf("cert = %+v", cert.Cert)
		}
	}
	// Lost response → retry returns the same certificates without a second debit.
	again, err := r.svc.Issue(ctx, "dev_a", ch, "acc_fortress_4402", req)
	if err != nil || len(again) != 3 || string(again[0].Certificate) != string(issued[0].Certificate) {
		t.Fatalf("retry: %v", err)
	}
	if len(r.ledger.Debits) != 1 {
		t.Fatalf("debits = %d", len(r.ledger.Debits))
	}
	for _, v := range r.ledger.Debits {
		if v != 75_000 {
			t.Fatalf("debited %d", v)
		}
	}
}

func TestIssueRejections(t *testing.T) {
	ctx := context.Background()
	t.Run("software-enforced single use", func(t *testing.T) {
		r := newIssueRig(t)
		ch, _ := r.svc.Challenge(ctx, "dev_a")
		if _, err := r.svc.Issue(ctx, "dev_a", ch, "acc", r.notes(t, ch, 0, 1_000)); !errors.Is(err, app.ErrAttestation) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("challenge from another device", func(t *testing.T) {
		r := newIssueRig(t)
		ch, _ := r.svc.Challenge(ctx, "dev_a")
		if _, err := r.svc.Issue(ctx, "dev_b", ch, "acc", r.notes(t, ch, 1, 1_000)); !errors.Is(err, app.ErrChallenge) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("above regulatory cap", func(t *testing.T) {
		r := newIssueRig(t)
		ch, _ := r.svc.Challenge(ctx, "dev_a")
		denoms := make([]uint64, 11)
		for i := range denoms {
			denoms[i] = 50_000
		}
		if _, err := r.svc.Issue(ctx, "dev_a", ch, "acc", r.notes(t, ch, 1, denoms...)); !errors.Is(err, app.ErrIssueInvalid) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("odd denomination", func(t *testing.T) {
		r := newIssueRig(t)
		ch, _ := r.svc.Challenge(ctx, "dev_a")
		if _, err := r.svc.Issue(ctx, "dev_a", ch, "acc", r.notes(t, ch, 1, 1_234)); !errors.Is(err, app.ErrIssueInvalid) {
			t.Fatalf("got %v", err)
		}
	})
}
