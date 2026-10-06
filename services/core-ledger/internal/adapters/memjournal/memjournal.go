// Package memjournal is an in-memory JournalStore for development and tests.
package memjournal

import (
	"context"
	"errors"
	"sync"

	"github.com/fortress-upi/fortress/services/core-ledger/internal/domain"
)

// ErrFull signals the dev store reached its bound.
var ErrFull = errors.New("memjournal: capacity reached")

// Store implements app.JournalStore.
type Store struct {
	mu       sync.Mutex
	byID     map[string][32]byte
	balances map[string]int64
	max      int
}

// New returns an empty store.
func New(maxJournals int) *Store {
	return &Store{byID: map[string][32]byte{}, balances: map[string]int64{}, max: maxJournals}
}

// Append implements app.JournalStore.
func (s *Store) Append(_ context.Context, j domain.Journal) (bool, error) {
	fp := j.Fingerprint()
	s.mu.Lock()
	defer s.mu.Unlock()
	if existing, ok := s.byID[j.ID]; ok {
		if existing != fp {
			return false, domain.ErrIdempotencyConflict
		}
		return false, nil
	}
	if len(s.byID) >= s.max {
		return false, ErrFull
	}
	s.byID[j.ID] = fp
	for _, e := range j.Entries {
		amt := int64(e.AmountPaise) //nolint:gosec // bounded by domain.MaxAmountPaise
		if e.Direction == domain.Debit {
			amt = -amt
		}
		s.balances[e.Account] += amt
	}
	return true, nil
}

// Balance returns an account balance in paise (tests).
func (s *Store) Balance(account string) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.balances[account]
}
