package domain_test

import (
	"crypto/sha256"
	"errors"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/testkit"
	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

var now = time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)

func policy() domain.VerifyPolicy {
	return domain.VerifyPolicy{
		Now:              now,
		SettlementGrace:  7 * 24 * time.Hour,
		MinEnforcement:   domain.EnforcementTEE,
		MaxBundleTotal:   500_000, // ₹5,000 in paise
		AllowRedemptions: true,
	}
}

type fixture struct {
	issuer *testkit.Issuer
	wallet domain.ID16
	notes  []*testkit.Note
}

func newFixture(t testing.TB, denoms ...uint64) fixture {
	t.Helper()
	iss, err := testkit.NewIssuer()
	if err != nil {
		t.Fatal(err)
	}
	f := fixture{issuer: iss, wallet: testkit.RandomID()}
	for _, d := range denoms {
		n, err := iss.IssueNote(f.wallet, d, now.Add(-time.Hour), now.Add(72*time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		f.notes = append(f.notes, n)
	}
	return f
}

func spec(f fixture, applied ...uint64) testkit.BundleSpec {
	return testkit.BundleSpec{
		PaymentID:  testkit.RandomID(),
		ReceiverID: testkit.RandomID(),
		Transcript: sha256.Sum256([]byte("transcript")),
		CreatedAt:  uint64(now.UnixMilli()),
		Notes:      f.notes,
		Applied:    applied,
	}
}

func mustParse(t testing.TB, raw []byte) *domain.Bundle {
	t.Helper()
	b, err := domain.ParseBundle(raw)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	return b
}

func TestPartialSpendSettlesWithRefund(t *testing.T) {
	f := newFixture(t, 10_000, 5_000) // ₹100 + ₹50
	raw, err := testkit.BuildBundle(spec(f, 10_000, 2_300))
	if err != nil {
		t.Fatal(err)
	}
	b := mustParse(t, raw)
	if err := b.Verify(f.issuer.Keyring(), policy()); err != nil {
		t.Fatalf("valid bundle rejected: %v", err)
	}
	s := b.Settlement(raw)
	if s.CreditPaise != 12_300 || s.RefundPaise != 2_700 || len(s.Claims) != 2 {
		t.Fatalf("unexpected settlement %+v", s)
	}
}

func TestRejections(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(f fixture, s *testkit.BundleSpec, p *domain.VerifyPolicy)
		want   error
	}{
		{"applied exceeds denomination", func(_ fixture, s *testkit.BundleSpec, _ *domain.VerifyPolicy) {
			s.Applied = []uint64{10_001}
			s.Notes = s.Notes[:1]
		}, domain.ErrAmount},
		{"zero applied in a payment", func(_ fixture, s *testkit.BundleSpec, _ *domain.VerifyPolicy) {
			s.Applied = []uint64{1_000, 0}
		}, domain.ErrAmount},
		{"over regulatory cap", func(_ fixture, _ *testkit.BundleSpec, p *domain.VerifyPolicy) {
			p.MaxBundleTotal = 100
		}, domain.ErrAmount},
		{"expired beyond grace", func(_ fixture, _ *testkit.BundleSpec, p *domain.VerifyPolicy) {
			p.Now = now.Add(72*time.Hour + 7*24*time.Hour + time.Second)
		}, domain.ErrExpired},
		{"unknown issuer", func(_ fixture, _ *testkit.BundleSpec, _ *domain.VerifyPolicy) {}, domain.ErrUnknownIssuer},
		{"duplicate note in bundle", func(f fixture, s *testkit.BundleSpec, _ *domain.VerifyPolicy) {
			s.Notes = []*testkit.Note{f.notes[0], f.notes[0]}
			s.Applied = []uint64{1_000, 1_000}
		}, domain.ErrDuplicateNote},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t, 10_000, 5_000)
			s := spec(f, 10_000, 2_300)
			p := policy()
			tc.mutate(f, &s, &p)
			raw, err := testkit.BuildBundle(s)
			if err != nil {
				t.Fatal(err)
			}
			keys := f.issuer.Keyring()
			if errors.Is(tc.want, domain.ErrUnknownIssuer) {
				other, _ := testkit.NewIssuer()
				keys = other.Keyring()
			}
			if err := mustParse(t, raw).Verify(keys, p); !errors.Is(err, tc.want) {
				t.Fatalf("got %v, want %v", err, tc.want)
			}
		})
	}
}

func TestTamperedAmountBreaksSpendSignature(t *testing.T) {
	f := newFixture(t, 10_000)
	s := spec(f, 4_000)
	n := f.notes[0]

	// Payer legitimately signs ₹40 ...
	body, err := domain.SpendSigBody(n.ID, s.PaymentID, s.ReceiverID, s.Transcript, 4_000, 4_000, s.CreatedAt)
	if err != nil {
		t.Fatal(err)
	}
	spendSig, err := sig.Sign(n.Priv, domains.Spend, body)
	if err != nil {
		t.Fatal(err)
	}
	// ... and an attacker re-encodes the bundle claiming ₹90 with that signature.
	item, err := fce.NewEncoder().Bytes(1, n.Signed).Uint64(2, 9_000).Bytes(3, spendSig).Finish()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := fce.NewEncoder().
		Bytes(1, s.PaymentID[:]).Bytes(2, s.Transcript[:]).Bytes(3, s.ReceiverID[:]).
		Uint64(4, 9_000).Uint64(5, s.CreatedAt).List(6, [][]byte{item}).Finish()
	if err != nil {
		t.Fatal(err)
	}
	if err := mustParse(t, raw).Verify(f.issuer.Keyring(), policy()); !errors.Is(err, domain.ErrSpendSignature) {
		t.Fatalf("got %v, want %v", err, domain.ErrSpendSignature)
	}
}

func TestMixedWalletsRejected(t *testing.T) {
	a := newFixture(t, 1_000)
	other, err := a.issuer.IssueNote(testkit.RandomID(), 1_000, now.Add(-time.Hour), now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	s := spec(a, 1_000, 1_000)
	s.Notes = []*testkit.Note{a.notes[0], other}
	raw, err := testkit.BuildBundle(s)
	if err != nil {
		t.Fatal(err)
	}
	if err := mustParse(t, raw).Verify(a.issuer.Keyring(), policy()); !errors.Is(err, domain.ErrMixedWallets) {
		t.Fatalf("got %v", err)
	}
}

func TestRedeemRefundsFullDenomination(t *testing.T) {
	f := newFixture(t, 20_000, 5_000)
	raw, err := testkit.BuildBundle(testkit.BundleSpec{
		PaymentID: testkit.RandomID(), CreatedAt: uint64(now.UnixMilli()),
		Notes: f.notes, Applied: []uint64{0, 0},
	})
	if err != nil {
		t.Fatal(err)
	}
	b := mustParse(t, raw)
	if err := b.Verify(f.issuer.Keyring(), policy()); err != nil {
		t.Fatalf("redeem rejected: %v", err)
	}
	if s := b.Settlement(raw); s.CreditPaise != 0 || s.RefundPaise != 25_000 {
		t.Fatalf("unexpected redeem settlement %+v", s)
	}
}

func TestMalformedInputIsRejectedNotPanicking(t *testing.T) {
	for _, raw := range [][]byte{nil, {0x01}, {0x06, 0, 0, 0, 4, 0, 0, 0, 0}} {
		if _, err := domain.ParseBundle(raw); !errors.Is(err, domain.ErrMalformed) {
			t.Fatalf("input %x: got %v", raw, err)
		}
	}
}

func BenchmarkVerifyTwoNoteBundle(b *testing.B) {
	f := newFixture(b, 10_000, 5_000)
	raw, err := testkit.BuildBundle(spec(f, 10_000, 2_300))
	if err != nil {
		b.Fatal(err)
	}
	keys := f.issuer.Keyring()
	p := policy()
	b.ReportAllocs()
	for b.Loop() {
		bundle, err := domain.ParseBundle(raw)
		if err != nil {
			b.Fatal(err)
		}
		if err := bundle.Verify(keys, p); err != nil {
			b.Fatal(err)
		}
	}
}
