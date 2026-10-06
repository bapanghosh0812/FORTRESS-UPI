package app_test

import (
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/adapters/memstore"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/app"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/testkit"
)

func TestSettleIsIdempotentAndDetectsDoubleSpend(t *testing.T) {
	now := time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)
	iss, err := testkit.NewIssuer()
	if err != nil {
		t.Fatal(err)
	}
	wallet := testkit.RandomID()
	note, err := iss.IssueNote(wallet, 10_000, now.Add(-time.Hour), now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}

	store := memstore.New(1_000)
	fraud := &memstore.LogFraud{}
	svc := app.NewSettleService(iss.Keyring(), store, fraud, func() time.Time { return now },
		app.Config{SettlementGrace: 24 * time.Hour, MinEnforcement: domain.EnforcementTEE, MaxBundleTotal: 500_000},
		slog.New(slog.NewTextHandler(io.Discard, nil)))

	pay := func(receiver domain.ID16, amount uint64) []byte {
		raw, err := testkit.BuildBundle(testkit.BundleSpec{
			PaymentID: testkit.RandomID(), ReceiverID: receiver,
			Transcript: sha256.Sum256(receiver[:]), CreatedAt: uint64(now.UnixMilli()),
			Notes: []*testkit.Note{note}, Applied: []uint64{amount},
		})
		if err != nil {
			t.Fatal(err)
		}
		return raw
	}

	ctx := context.Background()
	first := pay(testkit.RandomID(), 7_300)
	if out, err := svc.Submit(ctx, first); err != nil || out != domain.OutcomeSettled {
		t.Fatalf("first submit: %v %v", out, err)
	}
	// Payee and payer both upload the same bundle: idempotent.
	if out, err := svc.Submit(ctx, first); err != nil || out != domain.OutcomeDuplicate {
		t.Fatalf("replay: %v %v", out, err)
	}
	// Software-compromised device signs the same note twice for someone else.
	if out, err := svc.Submit(ctx, pay(testkit.RandomID(), 10_000)); err != nil || out != domain.OutcomeConflict {
		t.Fatalf("double spend: %v %v", out, err)
	}
	if len(fraud.Events) != 1 {
		t.Fatalf("fraud events = %d", len(fraud.Events))
	}
	settled := store.Settled()
	if len(settled) != 1 || settled[0].CreditPaise != 7_300 || settled[0].RefundPaise != 2_700 {
		t.Fatalf("ledger = %+v", settled)
	}
}

func TestSubmitRejectsGarbage(t *testing.T) {
	svc := app.NewSettleService(domain.StaticIssuerKeys{}, memstore.New(10), &memstore.LogFraud{},
		time.Now, app.Config{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if _, err := svc.Submit(context.Background(), []byte("garbage")); !errors.Is(err, app.ErrInvalid) {
		t.Fatalf("got %v", err)
	}
}
