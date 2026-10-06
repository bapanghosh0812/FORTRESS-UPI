package domain

import (
	"crypto/sha256"
	"fmt"
	"math/bits"
	"time"

	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// FCE tags — must match core/offline/protocol/Wire.kt.
const (
	tagBundlePaymentID  uint8 = 1
	tagBundleTranscript uint8 = 2
	tagBundleReceiverID uint8 = 3
	tagBundleTotal      uint8 = 4
	tagBundleCreatedAt  uint8 = 5
	tagBundleSpends     uint8 = 6

	tagSpendCert    uint8 = 1
	tagSpendApplied uint8 = 2
	tagSpendSig     uint8 = 3

	tagSpendSigNoteID     uint8 = 1
	tagSpendSigPaymentID  uint8 = 2
	tagSpendSigReceiverID uint8 = 3
	tagSpendSigTranscript uint8 = 4
	tagSpendSigApplied    uint8 = 5
	tagSpendSigTotal      uint8 = 6
	tagSpendSigCreatedAt  uint8 = 7

	// MaxSpendsPerBundle bounds verification cost per request.
	MaxSpendsPerBundle = 16
)

// Spend is one note's contribution to a payment.
type Spend struct {
	Note         SignedNoteCert
	AppliedPaise uint64
	Signature    []byte
	// Digest is SHA-256 of the spend signing input. It identifies the spend
	// independent of ECDSA signature malleability and is what note claims store.
	Digest [32]byte
	body   []byte
}

// Bundle is a complete offline payment.
type Bundle struct {
	PaymentID       ID16
	Transcript      [32]byte
	ReceiverID      ID16
	TotalPaise      uint64
	CreatedAtMillis uint64 // device clock — informational only, never trusted
	Spends          []Spend
}

// IsRedeem reports whether the bundle returns notes to the payer's wallet.
func (b *Bundle) IsRedeem() bool { return b.ReceiverID.IsZero() }

// ParseBundle decodes a bundle and rebuilds each spend's signing body.
func ParseBundle(raw []byte) (*Bundle, error) {
	m, err := fce.Decode(raw, tagBundlePaymentID, tagBundleTranscript, tagBundleReceiverID,
		tagBundleTotal, tagBundleCreatedAt, tagBundleSpends)
	if err != nil {
		return nil, fmt.Errorf("%w: bundle: %w", ErrMalformed, err)
	}
	b := &Bundle{}
	pid, err1 := m.Fixed(tagBundlePaymentID, 16)
	tr, err2 := m.Fixed(tagBundleTranscript, 32)
	rid, err3 := m.Fixed(tagBundleReceiverID, 16)
	total, err4 := m.Uint64(tagBundleTotal)
	created, err5 := m.Uint64(tagBundleCreatedAt)
	items, err6 := m.List(tagBundleSpends)
	for _, e := range []error{err1, err2, err3, err4, err5, err6} {
		if e != nil {
			return nil, fmt.Errorf("%w: bundle fields: %w", ErrMalformed, e)
		}
	}
	copy(b.PaymentID[:], pid)
	copy(b.Transcript[:], tr)
	copy(b.ReceiverID[:], rid)
	b.TotalPaise, b.CreatedAtMillis = total, created

	if len(items) == 0 || len(items) > MaxSpendsPerBundle {
		return nil, fmt.Errorf("%w: spend count %d", ErrMalformed, len(items))
	}
	b.Spends = make([]Spend, 0, len(items))
	for _, it := range items {
		s, err := parseSpend(it)
		if err != nil {
			return nil, err
		}
		s.body, err = SpendSigBody(s.Note.Cert.NoteID, b.PaymentID, b.ReceiverID, b.Transcript,
			s.AppliedPaise, b.TotalPaise, b.CreatedAtMillis)
		if err != nil {
			return nil, fmt.Errorf("%w: %w", ErrMalformed, err)
		}
		s.Digest = sig.Digest(domains.Spend, s.body)
		b.Spends = append(b.Spends, s)
	}
	return b, nil
}

func parseSpend(raw []byte) (Spend, error) {
	m, err := fce.Decode(raw, tagSpendCert, tagSpendApplied, tagSpendSig)
	if err != nil {
		return Spend{}, fmt.Errorf("%w: spend: %w", ErrMalformed, err)
	}
	certRaw, err1 := m.Bytes(tagSpendCert)
	applied, err2 := m.Uint64(tagSpendApplied)
	signature, err3 := m.BytesRange(tagSpendSig, 8, 80)
	for _, e := range []error{err1, err2, err3} {
		if e != nil {
			return Spend{}, fmt.Errorf("%w: spend fields: %w", ErrMalformed, e)
		}
	}
	note, err := ParseSignedNoteCert(certRaw)
	if err != nil {
		return Spend{}, err
	}
	return Spend{Note: note, AppliedPaise: applied, Signature: signature}, nil
}

// SpendSigBody builds the exact FCE body signed by a note key.
func SpendSigBody(noteID, paymentID, receiverID ID16, transcript [32]byte,
	applied, total, createdAtMillis uint64) ([]byte, error) {
	return fce.NewEncoder().
		Bytes(tagSpendSigNoteID, noteID[:]).
		Bytes(tagSpendSigPaymentID, paymentID[:]).
		Bytes(tagSpendSigReceiverID, receiverID[:]).
		Bytes(tagSpendSigTranscript, transcript[:]).
		Uint64(tagSpendSigApplied, applied).
		Uint64(tagSpendSigTotal, total).
		Uint64(tagSpendSigCreatedAt, createdAtMillis).
		Finish()
}

// VerifyPolicy carries settlement-time policy.
type VerifyPolicy struct {
	Now              time.Time
	SettlementGrace  time.Duration
	MinEnforcement   Enforcement
	MaxBundleTotal   uint64 // regulatory per-transaction cap, in paise
	AllowRedemptions bool
}

// Verify runs every structural, economic and cryptographic check. It is
// deliberately exhaustive: settlement is the last line of defense.
func (b *Bundle) Verify(keys IssuerKeys, p VerifyPolicy) error {
	redeem := b.IsRedeem()
	if redeem {
		if !p.AllowRedemptions || b.TotalPaise != 0 || b.Transcript != [32]byte{} {
			return ErrPolicy
		}
	} else if b.TotalPaise == 0 || (p.MaxBundleTotal > 0 && b.TotalPaise > p.MaxBundleTotal) {
		return ErrAmount
	}

	seen := make(map[ID16]struct{}, len(b.Spends))
	wallet := b.Spends[0].Note.Cert.WalletID
	var sum uint64
	for i := range b.Spends {
		s := &b.Spends[i]
		c := s.Note.Cert

		if _, dup := seen[c.NoteID]; dup {
			return ErrDuplicateNote
		}
		seen[c.NoteID] = struct{}{}
		if c.WalletID != wallet {
			return ErrMixedWallets
		}
		if c.Enforcement < p.MinEnforcement {
			return ErrPolicy
		}
		if p.Now.After(time.Unix(c.ExpiresAt, 0).Add(p.SettlementGrace)) {
			return ErrExpired
		}
		if redeem {
			if s.AppliedPaise != 0 {
				return ErrAmount
			}
		} else if s.AppliedPaise == 0 || s.AppliedPaise > c.DenominationPaise {
			return ErrAmount
		}
		var carry uint64
		sum, carry = bits.Add64(sum, s.AppliedPaise, 0)
		if carry != 0 {
			return ErrAmount
		}

		if err := s.Note.VerifyIssuer(keys); err != nil {
			return err
		}
		if err := sig.Verify(c.PublicKey, domains.Spend, s.body, s.Signature); err != nil {
			return ErrSpendSignature
		}
	}
	if sum != b.TotalPaise {
		return ErrAmount
	}
	return nil
}

// NoteClaim is the uniqueness record for a spent note.
type NoteClaim struct {
	NoteID ID16
	Digest [32]byte
}

// Settlement is the ledger effect of a verified bundle.
type Settlement struct {
	PaymentID   ID16
	ReceiverID  ID16 // zero for redemptions
	WalletID    ID16
	CreditPaise uint64 // to receiver
	RefundPaise uint64 // to payer wallet: Σ(denomination − applied)
	Claims      []NoteClaim
	BundleHash  [32]byte
}

// Settlement derives ledger instructions. Call only after Verify succeeds.
func (b *Bundle) Settlement(raw []byte) Settlement {
	s := Settlement{
		PaymentID:   b.PaymentID,
		ReceiverID:  b.ReceiverID,
		WalletID:    b.Spends[0].Note.Cert.WalletID,
		CreditPaise: b.TotalPaise,
		Claims:      make([]NoteClaim, 0, len(b.Spends)),
		BundleHash:  sha256.Sum256(raw),
	}
	for _, sp := range b.Spends {
		s.RefundPaise += sp.Note.Cert.DenominationPaise - sp.AppliedPaise
		s.Claims = append(s.Claims, NoteClaim{NoteID: sp.Note.Cert.NoteID, Digest: sp.Digest})
	}
	return s
}

// Outcome of a settlement attempt.
type Outcome int

const (
	OutcomeSettled   Outcome = iota + 1 // first time seen; ledger instructions emitted
	OutcomeDuplicate                    // identical spends already settled (idempotent replay)
	OutcomeConflict                     // a note was already settled with a different spend
)

func (o Outcome) String() string {
	switch o {
	case OutcomeSettled:
		return "SETTLED"
	case OutcomeDuplicate:
		return "DUPLICATE"
	case OutcomeConflict:
		return "CONFLICT"
	default:
		return "UNKNOWN"
	}
}
