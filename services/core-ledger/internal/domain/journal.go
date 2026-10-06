// Package domain defines Fortress double-entry posting rules. A journal is the
// atomic unit of money movement: every rupee debited is credited elsewhere in
// the same journal, amounts are integer paise, and the journal ID doubles as
// the idempotency key.
package domain

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"math/bits"
	"regexp"
)

// Direction of an entry.
type Direction uint8

const (
	Debit Direction = iota + 1
	Credit
)

// Entry is one leg of a journal.
type Entry struct {
	Account     string
	Direction   Direction
	AmountPaise uint64
}

// Journal is a balanced set of entries.
type Journal struct {
	ID        string // idempotency key, client-generated (UUIDv7 recommended)
	Narrative string
	Entries   []Entry
}

// Validation limits.
const (
	MinEntries   = 2
	MaxEntries   = 64
	MaxNarrative = 140
	// MaxAmountPaise caps a single entry at ₹1 crore — far above UPI limits,
	// low enough that Σ over MaxEntries cannot overflow.
	MaxAmountPaise = 1_000_000_000
)

var (
	idPattern      = regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}$`)
	accountPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9:._-]{2,127}$`)

	ErrInvalid             = errors.New("invalid journal")
	ErrUnbalanced          = errors.New("journal does not balance")
	ErrIdempotencyConflict = errors.New("journal id reused with different content")
)

// Validate enforces every posting invariant.
func (j Journal) Validate() error {
	if !idPattern.MatchString(j.ID) {
		return fmt.Errorf("%w: id", ErrInvalid)
	}
	if len(j.Narrative) > MaxNarrative {
		return fmt.Errorf("%w: narrative", ErrInvalid)
	}
	if len(j.Entries) < MinEntries || len(j.Entries) > MaxEntries {
		return fmt.Errorf("%w: entry count", ErrInvalid)
	}
	var debits, credits, carry uint64
	for i, e := range j.Entries {
		if !accountPattern.MatchString(e.Account) {
			return fmt.Errorf("%w: entry %d account", ErrInvalid, i)
		}
		if e.AmountPaise == 0 || e.AmountPaise > MaxAmountPaise {
			return fmt.Errorf("%w: entry %d amount", ErrInvalid, i)
		}
		switch e.Direction {
		case Debit:
			debits, carry = bits.Add64(debits, e.AmountPaise, 0)
		case Credit:
			credits, carry = bits.Add64(credits, e.AmountPaise, 0)
		default:
			return fmt.Errorf("%w: entry %d direction", ErrInvalid, i)
		}
		if carry != 0 {
			return fmt.Errorf("%w: overflow", ErrInvalid)
		}
	}
	if debits != credits {
		return ErrUnbalanced
	}
	return nil
}

// Fingerprint is a stable content hash used to detect idempotency-key reuse
// with different content.
func (j Journal) Fingerprint() [32]byte {
	h := sha256.New()
	var n [8]byte
	write := func(s string) {
		binary.BigEndian.PutUint64(n[:], uint64(len(s))) //nolint:gosec // len is non-negative
		h.Write(n[:])
		h.Write([]byte(s))
	}
	write(j.ID)
	write(j.Narrative)
	for _, e := range j.Entries {
		write(e.Account)
		h.Write([]byte{byte(e.Direction)})
		binary.BigEndian.PutUint64(n[:], e.AmountPaise)
		h.Write(n[:])
	}
	var out [32]byte
	copy(out[:], h.Sum(nil))
	return out
}
