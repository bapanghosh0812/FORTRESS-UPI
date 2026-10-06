package domain_test

import (
	"errors"
	"testing"

	"github.com/fortress-upi/fortress/services/core-ledger/internal/domain"
)

func balanced() domain.Journal {
	return domain.Journal{
		ID:        "01JABCDEF0123456",
		Narrative: "offline settlement",
		Entries: []domain.Entry{
			{Account: "pool:offline:wallet-1", Direction: domain.Debit, AmountPaise: 10_000},
			{Account: "user:receiver-9", Direction: domain.Credit, AmountPaise: 7_300},
			{Account: "user:wallet-1", Direction: domain.Credit, AmountPaise: 2_700},
		},
	}
}

func TestBalancedJournalIsValid(t *testing.T) {
	if err := balanced().Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestInvariants(t *testing.T) {
	cases := map[string]struct {
		mutate func(*domain.Journal)
		want   error
	}{
		"unbalanced":      {func(j *domain.Journal) { j.Entries[1].AmountPaise = 7_301 }, domain.ErrUnbalanced},
		"zero amount":     {func(j *domain.Journal) { j.Entries[0].AmountPaise = 0 }, domain.ErrInvalid},
		"single entry":    {func(j *domain.Journal) { j.Entries = j.Entries[:1] }, domain.ErrInvalid},
		"bad account":     {func(j *domain.Journal) { j.Entries[0].Account = "DROP TABLE" }, domain.ErrInvalid},
		"bad direction":   {func(j *domain.Journal) { j.Entries[0].Direction = 9 }, domain.ErrInvalid},
		"bad id":          {func(j *domain.Journal) { j.ID = "x" }, domain.ErrInvalid},
		"over max amount": {func(j *domain.Journal) { j.Entries[0].AmountPaise = domain.MaxAmountPaise + 1 }, domain.ErrInvalid},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			j := balanced()
			tc.mutate(&j)
			if err := j.Validate(); !errors.Is(err, tc.want) {
				t.Fatalf("got %v, want %v", err, tc.want)
			}
		})
	}
}

func TestFingerprintChangesWithContent(t *testing.T) {
	a, b := balanced(), balanced()
	b.Entries[1].Account = "user:receiver-8"
	if a.Fingerprint() == b.Fingerprint() {
		t.Fatal("fingerprint ignored account change")
	}
}
