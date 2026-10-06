// Package app holds the ledger use cases.
package app

import (
	"context"

	"github.com/fortress-upi/fortress/services/core-ledger/internal/domain"
)

// JournalStore persists journals idempotently. Append returns created=false
// when an identical journal (same ID and fingerprint) already exists, and
// domain.ErrIdempotencyConflict when the ID exists with different content.
// The CockroachDB adapter writes the journal, its entries, balance deltas and a
// transactional-outbox event in one serializable transaction.
type JournalStore interface {
	Append(ctx context.Context, j domain.Journal) (created bool, err error)
}

// PostService posts journals.
type PostService struct{ store JournalStore }

// NewPostService wires the use case.
func NewPostService(store JournalStore) *PostService { return &PostService{store: store} }

// Post validates and appends j.
func (s *PostService) Post(ctx context.Context, j domain.Journal) (bool, error) {
	if err := j.Validate(); err != nil {
		return false, err
	}
	return s.store.Append(ctx, j)
}
