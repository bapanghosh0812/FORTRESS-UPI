// Package app holds payment use cases: create (idempotent), authorize with the
// NPCI CL credential, status (with PSP polling), PSP callbacks and history.
package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/fortress-upi/fortress/services/payments/internal/domain"
)

// Store persists payments. Production: CockroachDB with
// UNIQUE(device_id, idempotency_key) and conditional (CAS) state updates.
type Store interface {
	// Create inserts p, or returns the existing payment for the same (device, key).
	Create(ctx context.Context, p domain.Payment) (domain.Payment, bool, error)
	Get(ctx context.Context, id string) (domain.Payment, bool, error)
	// Update replaces p only if the stored state still equals expected.
	Update(ctx context.Context, p domain.Payment, expected domain.State) (bool, error)
	ByPSPRef(ctx context.Context, ref string) (domain.Payment, bool, error)
	ListByDevice(ctx context.Context, deviceID string, limit int) ([]domain.Payment, error)
}

// PSPResult is the sponsor bank's view of a payment.
type PSPResult struct {
	State  domain.State
	UPIRef string
	Reason string
}

// PSP is the sponsor PSP bank's UPI API (ReqPay / ReqChkTxn under the hood).
type PSP interface {
	Pay(ctx context.Context, p domain.Payment, credential string) (pspRef string, err error)
	Status(ctx context.Context, pspRef string) (PSPResult, error)
}

// Account is a bank account linked to the device's UPI profile.
type Account struct {
	Ref     string
	Bank    string
	Masked  string
	Primary bool
}

// Accounts lists linked accounts (PSP account discovery, cached per device).
type Accounts interface {
	List(ctx context.Context, deviceID string) ([]Account, error)
}

// Config is payment policy.
type Config struct {
	MaxPaise uint64 // per-transaction cap (bank/NPCI limits apply on top)
}

// Service orchestrates payments.
type Service struct {
	store    Store
	psp      PSP
	accounts Accounts
	now      func() time.Time
	cfg      Config
	log      *slog.Logger
}

// New wires the service.
func New(store Store, psp PSP, accounts Accounts, now func() time.Time, cfg Config, log *slog.Logger) *Service {
	return &Service{store: store, psp: psp, accounts: accounts, now: now, cfg: cfg, log: log}
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// Create registers a payment and returns the CL challenge for PIN capture.
func (s *Service) Create(ctx context.Context, p domain.Payment) (domain.Payment, error) {
	if err := p.Validate(s.cfg.MaxPaise); err != nil {
		return domain.Payment{}, err
	}
	if !s.ownsAccount(ctx, p.DeviceID, p.AccountRef) {
		return domain.Payment{}, fmt.Errorf("%w: account", domain.ErrInvalid)
	}
	id, err := randomHex(16)
	if err != nil {
		return domain.Payment{}, err
	}
	challenge, err := randomHex(16)
	if err != nil {
		return domain.Payment{}, err
	}
	now := s.now()
	p.ID, p.CLChallenge, p.State, p.CreatedAt, p.UpdatedAt = "pay_"+id, challenge, domain.Created, now, now
	stored, created, err := s.store.Create(ctx, p)
	if err != nil {
		return domain.Payment{}, err
	}
	if !created && !stored.SameRequest(p) {
		return domain.Payment{}, domain.ErrIdempotency
	}
	return stored, nil
}

// Authorize submits the CL credential block to the PSP. Retries are safe:
// a payment leaves CREATED exactly once.
func (s *Service) Authorize(ctx context.Context, deviceID, id, credential string) (domain.Payment, error) {
	p, err := s.owned(ctx, deviceID, id)
	if err != nil {
		return domain.Payment{}, err
	}
	if p.State != domain.Created {
		return p, nil // idempotent retry
	}
	if credential == "" || len(credential) > 8<<10 {
		return domain.Payment{}, fmt.Errorf("%w: credential", domain.ErrInvalid)
	}
	ref, err := s.psp.Pay(ctx, p, credential)
	if err != nil {
		if errors.Is(err, domain.ErrCredential) {
			p.Reason = "credential rejected"
			if terr := p.Transition(domain.Failed, s.now()); terr == nil {
				_, _ = s.store.Update(ctx, p, domain.Created)
			}
			return p, nil
		}
		return domain.Payment{}, fmt.Errorf("psp pay: %w", err)
	}
	p.PSPRef = ref
	if err := p.Transition(domain.Pending, s.now()); err != nil {
		return domain.Payment{}, err
	}
	if ok, err := s.store.Update(ctx, p, domain.Created); err != nil {
		return domain.Payment{}, err
	} else if !ok {
		return s.owned(ctx, deviceID, id)
	}
	return p, nil
}

// Status returns the payment, refreshing non-final states from the PSP.
func (s *Service) Status(ctx context.Context, deviceID, id string) (domain.Payment, error) {
	p, err := s.owned(ctx, deviceID, id)
	if err != nil || p.State.Final() || p.PSPRef == "" {
		return p, err
	}
	res, err := s.psp.Status(ctx, p.PSPRef)
	if err != nil {
		s.log.WarnContext(ctx, "psp status check failed", "payment_id", p.ID, "err", err)
		return p, nil // keep last known state; the client keeps polling
	}
	return s.apply(ctx, p, res)
}

// Callback applies an authenticated PSP notification.
func (s *Service) Callback(ctx context.Context, pspRef string, res PSPResult) error {
	p, ok, err := s.store.ByPSPRef(ctx, pspRef)
	if err != nil {
		return err
	}
	if !ok {
		return domain.ErrNotFound
	}
	_, err = s.apply(ctx, p, res)
	return err
}

func (s *Service) apply(ctx context.Context, p domain.Payment, res PSPResult) (domain.Payment, error) {
	if res.State == p.State || res.State == domain.Pending {
		return p, nil
	}
	prev := p.State
	if err := p.Transition(res.State, s.now()); err != nil {
		s.log.WarnContext(ctx, "ignored illegal PSP transition", "payment_id", p.ID, "err", err)
		return p, nil
	}
	p.UPIRef, p.Reason = res.UPIRef, res.Reason
	if ok, err := s.store.Update(ctx, p, prev); err != nil {
		return domain.Payment{}, err
	} else if !ok {
		latest, _, err := s.store.Get(ctx, p.ID)
		return latest, err
	}
	return p, nil
}

// Transactions lists the device's recent payments, newest first.
func (s *Service) Transactions(ctx context.Context, deviceID string) ([]domain.Payment, error) {
	return s.store.ListByDevice(ctx, deviceID, 100)
}

// Accounts lists linked accounts.
func (s *Service) Accounts(ctx context.Context, deviceID string) ([]Account, error) {
	return s.accounts.List(ctx, deviceID)
}

func (s *Service) owned(ctx context.Context, deviceID, id string) (domain.Payment, error) {
	if !strings.HasPrefix(id, "pay_") {
		return domain.Payment{}, domain.ErrNotFound
	}
	p, ok, err := s.store.Get(ctx, id)
	if err != nil {
		return domain.Payment{}, err
	}
	if !ok || p.DeviceID != deviceID {
		// Same response for "missing" and "someone else's": no enumeration oracle.
		return domain.Payment{}, domain.ErrNotFound
	}
	return p, nil
}

func (s *Service) ownsAccount(ctx context.Context, deviceID, ref string) bool {
	accts, err := s.accounts.List(ctx, deviceID)
	if err != nil {
		return false
	}
	for _, a := range accts {
		if a.Ref == ref {
			return true
		}
	}
	return false
}
