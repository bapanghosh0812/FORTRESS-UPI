// Package memstore is an in-memory payment store for development and tests.
package memstore

import (
	"context"
	"sort"
	"sync"

	"github.com/fortress-upi/fortress/services/payments/internal/domain"
)

// Store implements app.Store.
type Store struct {
	mu     sync.Mutex
	byID   map[string]domain.Payment
	byIdem map[string]string // device|key → id
	byPSP  map[string]string
}

// New returns an empty store.
func New() *Store {
	return &Store{byID: map[string]domain.Payment{}, byIdem: map[string]string{}, byPSP: map[string]string{}}
}

// Create implements app.Store.
func (s *Store) Create(_ context.Context, p domain.Payment) (domain.Payment, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k := p.DeviceID + "|" + p.IdempotencyKey
	if id, ok := s.byIdem[k]; ok {
		return s.byID[id], false, nil
	}
	s.byID[p.ID] = p
	s.byIdem[k] = p.ID
	return p, true, nil
}

// Get implements app.Store.
func (s *Store) Get(_ context.Context, id string) (domain.Payment, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.byID[id]
	return p, ok, nil
}

// Update implements app.Store (compare-and-set on state).
func (s *Store) Update(_ context.Context, p domain.Payment, expected domain.State) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur, ok := s.byID[p.ID]
	if !ok || cur.State != expected {
		return false, nil
	}
	s.byID[p.ID] = p
	if p.PSPRef != "" {
		s.byPSP[p.PSPRef] = p.ID
	}
	return true, nil
}

// ByPSPRef implements app.Store.
func (s *Store) ByPSPRef(_ context.Context, ref string) (domain.Payment, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	id, ok := s.byPSP[ref]
	if !ok {
		return domain.Payment{}, false, nil
	}
	return s.byID[id], true, nil
}

// ListByDevice implements app.Store.
func (s *Store) ListByDevice(_ context.Context, deviceID string, limit int) ([]domain.Payment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []domain.Payment
	for _, p := range s.byID {
		if p.DeviceID == deviceID {
			out = append(out, p)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
