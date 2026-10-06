package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/fortress-upi/fortress/services/core-ledger/internal/adapters/memjournal"
	"github.com/fortress-upi/fortress/services/core-ledger/internal/app"
	"github.com/fortress-upi/fortress/services/core-ledger/internal/domain"
)

func journal(credit string) domain.Journal {
	return domain.Journal{ID: "01JIDEMPOTENT01", Entries: []domain.Entry{
		{Account: "user:payer-1", Direction: domain.Debit, AmountPaise: 500},
		{Account: credit, Direction: domain.Credit, AmountPaise: 500},
	}}
}

func TestPostIsIdempotentAndBalancesMove(t *testing.T) {
	store := memjournal.New(100)
	svc := app.NewPostService(store)
	ctx := context.Background()

	if created, err := svc.Post(ctx, journal("user:payee-1")); err != nil || !created {
		t.Fatalf("first post: %v %v", created, err)
	}
	if created, err := svc.Post(ctx, journal("user:payee-1")); err != nil || created {
		t.Fatalf("replay must be a no-op: %v %v", created, err)
	}
	if _, err := svc.Post(ctx, journal("user:attacker")); !errors.Is(err, domain.ErrIdempotencyConflict) {
		t.Fatalf("id reuse with new content: %v", err)
	}
	if store.Balance("user:payer-1") != -500 || store.Balance("user:payee-1") != 500 {
		t.Fatal("balances not moved exactly once")
	}
}

func TestPostRejectsInvalid(t *testing.T) {
	j := journal("user:payee-1")
	j.Entries[1].AmountPaise = 499
	if _, err := app.NewPostService(memjournal.New(1)).Post(context.Background(), j); !errors.Is(err, domain.ErrUnbalanced) {
		t.Fatalf("got %v", err)
	}
}
