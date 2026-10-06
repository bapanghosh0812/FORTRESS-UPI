// Package memstore is an atomic in-memory settlement store for development,
// tests and CI load runs. It is refused at startup when FORTRESS_ENV=production.
package memstore

import (
	"context"
	"errors"
	"sync"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
)

// ErrCorrupt signals an impossible partial state (would indicate a store bug).
var ErrCorrupt = errors.New("memstore: inconsistent partial settlement")

type claim struct {
	digest  [32]byte
	payment domain.ID16
}

// Store implements app.Store.
type Store struct {
	mu      sync.Mutex
	claims  map[domain.ID16]claim
	ledger  []domain.Settlement
	maxSize int
}

// New returns an empty store bounded to maxClaims entries.
func New(maxClaims int) *Store {
	return &Store{claims: make(map[domain.ID16]claim), maxSize: maxClaims}
}

// ErrFull is returned when the dev store reaches its bound (fail closed).
var ErrFull = errors.New("memstore: capacity reached")

// Settle claims every note atomically.
func (s *Store) Settle(_ context.Context, st domain.Settlement) (domain.Outcome, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	matched := 0
	for _, c := range st.Claims {
		existing, ok := s.claims[c.NoteID]
		if !ok {
			continue
		}
		if existing.digest != c.Digest {
			return domain.OutcomeConflict, nil
		}
		matched++
	}
	switch {
	case matched == len(st.Claims):
		return domain.OutcomeDuplicate, nil
	case matched > 0:
		// Same digest implies same payment; a partial match means a prior
		// non-atomic write, which this store never performs.
		return 0, ErrCorrupt
	}
	if len(s.claims)+len(st.Claims) > s.maxSize {
		return 0, ErrFull
	}
	for _, c := range st.Claims {
		s.claims[c.NoteID] = claim{digest: c.Digest, payment: st.PaymentID}
	}
	s.ledger = append(s.ledger, st)
	return domain.OutcomeSettled, nil
}

// Settled returns a copy of recorded settlements (tests).
func (s *Store) Settled() []domain.Settlement {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]domain.Settlement, len(s.ledger))
	copy(out, s.ledger)
	return out
}

// LogFraud is a FraudSink that only counts events (dev).
type LogFraud struct {
	mu     sync.Mutex
	Events []domain.Settlement
}

// DoubleSpend implements app.FraudSink.
func (l *LogFraud) DoubleSpend(_ context.Context, s domain.Settlement) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.Events = append(l.Events, s)
}
