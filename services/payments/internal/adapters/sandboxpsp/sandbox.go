// Package sandboxpsp simulates a sponsor PSP bank for development, CI and the
// debug app build. It accepts only the debug app's marker credential
// ("sandbox:<clChallenge>") and never moves money.
//
// Deterministic test hooks: amounts ending in 13 paise fail (U30 debit
// failure); amounts ending in 77 paise go DEEMED.
package sandboxpsp

import (
	"context"
	"crypto/rand"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/fortress-upi/fortress/services/payments/internal/app"
	"github.com/fortress-upi/fortress/services/payments/internal/domain"
)

type entry struct {
	amount  uint64
	settles time.Time
	upiRef  string
}

// PSP implements app.PSP and app.Accounts.
type PSP struct {
	mu      sync.Mutex
	entries map[string]entry
	now     func() time.Time
	latency time.Duration
}

// New returns a sandbox PSP that settles after latency.
func New(now func() time.Time, latency time.Duration) *PSP {
	return &PSP{entries: map[string]entry{}, now: now, latency: latency}
}

func digits(n int) (string, error) {
	out := make([]byte, n)
	for i := range out {
		d, err := rand.Int(rand.Reader, big.NewInt(10))
		if err != nil {
			return "", err
		}
		out[i] = byte('0' + d.Int64())
	}
	return string(out), nil
}

// Pay implements app.PSP.
func (s *PSP) Pay(_ context.Context, p domain.Payment, credential string) (string, error) {
	if credential != "sandbox:"+p.CLChallenge {
		return "", domain.ErrCredential
	}
	ref, err := digits(12)
	if err != nil {
		return "", err
	}
	upi, err := digits(12)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries["psp_"+ref] = entry{amount: p.AmountPaise, settles: s.now().Add(s.latency), upiRef: upi}
	return "psp_" + ref, nil
}

// Status implements app.PSP.
func (s *PSP) Status(_ context.Context, ref string) (app.PSPResult, error) {
	s.mu.Lock()
	e, ok := s.entries[ref]
	s.mu.Unlock()
	if !ok {
		return app.PSPResult{}, fmt.Errorf("sandbox: unknown ref %q", ref)
	}
	if s.now().Before(e.settles) {
		return app.PSPResult{State: domain.Pending}, nil
	}
	switch e.amount % 100 {
	case 13:
		return app.PSPResult{State: domain.Failed, Reason: "U30 debit failed at remitter bank"}, nil
	case 77:
		return app.PSPResult{State: domain.Deemed}, nil
	default:
		return app.PSPResult{State: domain.Success, UPIRef: e.upiRef}, nil
	}
}

// List implements app.Accounts with fictional sandbox accounts.
func (s *PSP) List(_ context.Context, _ string) ([]app.Account, error) {
	return []app.Account{
		{Ref: "acc_fortress_4402", Bank: "Fortress Bank", Masked: "•• 4402", Primary: true},
		{Ref: "acc_harbor_1187", Bank: "Harbor Co-operative Bank", Masked: "•• 1187"},
	}, nil
}
