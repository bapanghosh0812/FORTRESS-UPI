// Package domain models an online UPI payment's lifecycle as Fortress tracks
// it while the sponsor PSP bank and NPCI execute it.
package domain

import (
	"errors"
	"fmt"
	"regexp"
	"time"
	"unicode/utf8"
)

// State of a payment. DEEMED follows NPCI semantics: the outcome is not yet
// known after the switch timeout; status checks continue and the user is told
// not to retry.
type State string

const (
	Created State = "CREATED"
	Pending State = "PENDING"
	Success State = "SUCCESS"
	Failed  State = "FAILED"
	Deemed  State = "DEEMED"
)

// Payment is one online UPI pay request.
type Payment struct {
	ID             string
	DeviceID       string
	IdempotencyKey string
	AccountRef     string
	PayeeVPA       string
	PayeeName      string
	AmountPaise    uint64
	Note           string
	MerchantCode   string
	TxnRef         string
	CLChallenge    string
	State          State
	PSPRef         string
	UPIRef         string
	Reason         string
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

// Errors.
var (
	ErrInvalid     = errors.New("invalid_payment")
	ErrNotFound    = errors.New("not_found")
	ErrState       = errors.New("invalid_state")
	ErrIdempotency = errors.New("idempotency_conflict")
	ErrCredential  = errors.New("credential_rejected")
)

var (
	vpaPattern     = regexp.MustCompile(`^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,64}$`)
	mccPattern     = regexp.MustCompile(`^\d{4}$`)
	txnRefPattern  = regexp.MustCompile(`^[A-Za-z0-9._-]{1,35}$`)
	idemKeyPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,80}$`)
)

// Validate enforces request invariants. maxPaise is the per-transaction limit.
func (p Payment) Validate(maxPaise uint64) error {
	switch {
	case !vpaPattern.MatchString(p.PayeeVPA):
		return fmt.Errorf("%w: payee", ErrInvalid)
	case p.AmountPaise == 0 || p.AmountPaise > maxPaise:
		return fmt.Errorf("%w: amount", ErrInvalid)
	case !idemKeyPattern.MatchString(p.IdempotencyKey):
		return fmt.Errorf("%w: idempotency key", ErrInvalid)
	case p.AccountRef == "" || len(p.AccountRef) > 64:
		return fmt.Errorf("%w: account", ErrInvalid)
	case utf8.RuneCountInString(p.PayeeName) > 99 || utf8.RuneCountInString(p.Note) > 80:
		return fmt.Errorf("%w: text too long", ErrInvalid)
	case p.MerchantCode != "" && !mccPattern.MatchString(p.MerchantCode):
		return fmt.Errorf("%w: merchant code", ErrInvalid)
	case p.TxnRef != "" && !txnRefPattern.MatchString(p.TxnRef):
		return fmt.Errorf("%w: txn ref", ErrInvalid)
	}
	return nil
}

var transitions = map[State][]State{
	Created: {Pending, Failed},
	Pending: {Success, Failed, Deemed},
	Deemed:  {Success, Failed},
}

// Transition moves the payment to next, enforcing the lifecycle graph.
func (p *Payment) Transition(next State, at time.Time) error {
	for _, allowed := range transitions[p.State] {
		if allowed == next {
			p.State, p.UpdatedAt = next, at
			return nil
		}
	}
	return fmt.Errorf("%w: %s → %s", ErrState, p.State, next)
}

// Final reports whether the state can no longer change.
func (s State) Final() bool { return s == Success || s == Failed }

// SameRequest reports whether two creates carry identical business content
// (used to tell a safe idempotent retry from key reuse).
func (p Payment) SameRequest(o Payment) bool {
	return p.DeviceID == o.DeviceID && p.AccountRef == o.AccountRef && p.PayeeVPA == o.PayeeVPA &&
		p.AmountPaise == o.AmountPaise && p.Note == o.Note && p.MerchantCode == o.MerchantCode && p.TxnRef == o.TxnRef
}
