// Package memstore is an in-memory binding store for development and tests.
package memstore

import (
	"context"
	"sync"
	"time"

	"github.com/fortress-upi/fortress/services/identity/internal/domain"
)

// Store implements app.Store.
type Store struct {
	mu       sync.Mutex
	sessions map[[16]byte]domain.Session
	byToken  map[string][16]byte
	devices  map[string]domain.Device
}

// New returns an empty store.
func New() *Store {
	return &Store{sessions: map[[16]byte]domain.Session{}, byToken: map[string][16]byte{}, devices: map[string]domain.Device{}}
}

// CreateSession implements app.Store.
func (s *Store) CreateSession(_ context.Context, sess domain.Session) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[sess.ID] = sess
	s.byToken[sess.SMSToken] = sess.ID
	return nil
}

// SessionByID implements app.Store.
func (s *Store) SessionByID(_ context.Context, id [16]byte) (domain.Session, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[id]
	return sess, ok, nil
}

// AttachMSISDN implements app.Store.
func (s *Store) AttachMSISDN(_ context.Context, token, msisdn string, now time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	id, ok := s.byToken[token]
	if !ok {
		return false, nil
	}
	sess := s.sessions[id]
	if sess.Completed || now.After(sess.ExpiresAt) || sess.MSISDN != "" {
		return false, nil
	}
	sess.MSISDN = msisdn
	s.sessions[id] = sess
	return true, nil
}

// CompleteSession implements app.Store.
func (s *Store) CompleteSession(_ context.Context, id [16]byte) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[id]
	if !ok || sess.Completed {
		return false, nil
	}
	sess.Completed = true
	s.sessions[id] = sess
	delete(s.byToken, sess.SMSToken)
	return true, nil
}

// SaveDevice implements app.Store: one active device per MSISDN.
func (s *Store) SaveDevice(_ context.Context, d domain.Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, other := range s.devices {
		if other.MSISDN == d.MSISDN && other.Active {
			other.Active = false
			s.devices[id] = other
		}
	}
	s.devices[d.ID] = d
	return nil
}

// Device implements app.Store.
func (s *Store) Device(_ context.Context, id string) (domain.Device, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[id]
	return d, ok, nil
}
