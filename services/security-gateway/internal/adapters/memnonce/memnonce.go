// Package memnonce is a bounded in-memory nonce store for development and
// tests. Production uses Redis `SET key 1 NX PX ttl` (shared across replicas).
package memnonce

import (
	"context"
	"errors"
	"sync"
	"time"
)

// ErrFull is returned when the store is at capacity even after a sweep. The
// verifier treats it as a rejection (fail closed).
var ErrFull = errors.New("memnonce: capacity reached")

// Store implements devicesig.NonceStore.
type Store struct {
	mu      sync.Mutex
	entries map[string]time.Time
	max     int
	now     func() time.Time
}

// New returns a store holding at most max live nonces.
func New(maxEntries int, now func() time.Time) *Store {
	return &Store{entries: make(map[string]time.Time), max: maxEntries, now: now}
}

// Remember records key and reports whether it was unseen (or expired).
func (s *Store) Remember(_ context.Context, key string, ttl time.Duration) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	if exp, ok := s.entries[key]; ok && now.Before(exp) {
		return false, nil
	}
	if len(s.entries) >= s.max {
		for k, exp := range s.entries {
			if !now.Before(exp) {
				delete(s.entries, k)
			}
		}
		if len(s.entries) >= s.max {
			return false, ErrFull
		}
	}
	s.entries[key] = now.Add(ttl)
	return true, nil
}
