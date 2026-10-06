// Package domain contains the pure offline-settlement model: parsing and full
// cryptographic verification of payment bundles produced by the Fortress
// offline engine (docs/OFFLINE_PROTOCOL.md). It performs no I/O and reads no
// clock; callers inject time and issuer keys.
package domain

import (
	"crypto/ecdsa"
	"encoding/hex"
	"errors"
	"fmt"

	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// FCE tags — must match core/offline/protocol/Wire.kt.
const (
	tagEnvBody uint8 = 1
	tagEnvSig  uint8 = 2

	tagCertNoteID       uint8 = 1
	tagCertWalletID     uint8 = 2
	tagCertPublicKey    uint8 = 3
	tagCertDenomination uint8 = 4
	tagCertIssuedAt     uint8 = 5
	tagCertExpiresAt    uint8 = 6
	tagCertIssuerKeyID  uint8 = 7
	tagCertEnforcement  uint8 = 8
)

// Enforcement describes how single-use is enforced for a note key.
type Enforcement uint64

const (
	EnforcementSoftware  Enforcement = 0
	EnforcementTEE       Enforcement = 1
	EnforcementStrongBox Enforcement = 2
)

// ID16 is a 128-bit identifier (note, wallet, receiver, payment).
type ID16 [16]byte

func (id ID16) String() string { return hex.EncodeToString(id[:]) }

// IsZero reports whether the identifier is all zeros (redeem sentinel).
func (id ID16) IsZero() bool { return id == ID16{} }

// NoteCert is an issuer-certified binding of a single-use device key to value.
type NoteCert struct {
	NoteID            ID16
	WalletID          ID16
	PublicKey         *ecdsa.PublicKey
	DenominationPaise uint64
	IssuedAt          int64 // unix seconds
	ExpiresAt         int64 // unix seconds
	IssuerKeyID       []byte
	Enforcement       Enforcement
}

// SignedNoteCert keeps the exact signed bytes alongside the parsed form.
type SignedNoteCert struct {
	Body      []byte
	Signature []byte
	Cert      NoteCert
}

// IssuerKeys resolves issuer key IDs to public keys (HSM-held private halves).
type IssuerKeys interface {
	Lookup(keyID []byte) (*ecdsa.PublicKey, bool)
}

// StaticIssuerKeys is an immutable keyring keyed by hex key ID.
type StaticIssuerKeys map[string]*ecdsa.PublicKey

// Lookup implements IssuerKeys.
func (s StaticIssuerKeys) Lookup(keyID []byte) (*ecdsa.PublicKey, bool) {
	k, ok := s[hex.EncodeToString(keyID)]
	return k, ok
}

// Validation errors. All map to HTTP 422 with a stable code; none leak which
// cryptographic check failed beyond what the submitter already knows.
var (
	ErrMalformed       = errors.New("malformed")
	ErrUnknownIssuer   = errors.New("unknown_issuer")
	ErrIssuerSignature = errors.New("issuer_signature")
	ErrSpendSignature  = errors.New("spend_signature")
	ErrExpired         = errors.New("note_expired")
	ErrAmount          = errors.New("amount_invariant")
	ErrDuplicateNote   = errors.New("duplicate_note")
	ErrMixedWallets    = errors.New("mixed_wallets")
	ErrPolicy          = errors.New("policy")
)

// ParseSignedNoteCert decodes a note-certificate envelope.
func ParseSignedNoteCert(b []byte) (SignedNoteCert, error) {
	env, err := fce.Decode(b, tagEnvBody, tagEnvSig)
	if err != nil {
		return SignedNoteCert{}, fmt.Errorf("%w: envelope: %w", ErrMalformed, err)
	}
	body, err := env.Bytes(tagEnvBody)
	if err != nil {
		return SignedNoteCert{}, fmt.Errorf("%w: %w", ErrMalformed, err)
	}
	signature, err := env.BytesRange(tagEnvSig, 8, 80)
	if err != nil {
		return SignedNoteCert{}, fmt.Errorf("%w: %w", ErrMalformed, err)
	}
	cert, err := parseNoteCertBody(body)
	if err != nil {
		return SignedNoteCert{}, err
	}
	return SignedNoteCert{Body: body, Signature: signature, Cert: cert}, nil
}

func parseNoteCertBody(body []byte) (NoteCert, error) {
	m, err := fce.Decode(body,
		tagCertNoteID, tagCertWalletID, tagCertPublicKey, tagCertDenomination,
		tagCertIssuedAt, tagCertExpiresAt, tagCertIssuerKeyID, tagCertEnforcement)
	if err != nil {
		return NoteCert{}, fmt.Errorf("%w: cert: %w", ErrMalformed, err)
	}
	var c NoteCert
	errs := make([]error, 0, 8)
	collect := func(err error) {
		if err != nil {
			errs = append(errs, err)
		}
	}

	noteID, err := m.Fixed(tagCertNoteID, 16)
	collect(err)
	walletID, err := m.Fixed(tagCertWalletID, 16)
	collect(err)
	spki, err := m.BytesRange(tagCertPublicKey, 1, 256)
	collect(err)
	c.DenominationPaise, err = m.Uint64(tagCertDenomination)
	collect(err)
	issued, err := m.Uint64(tagCertIssuedAt)
	collect(err)
	expires, err := m.Uint64(tagCertExpiresAt)
	collect(err)
	c.IssuerKeyID, err = m.BytesRange(tagCertIssuerKeyID, 1, 32)
	collect(err)
	enf, err := m.Uint64(tagCertEnforcement)
	collect(err)
	if len(errs) > 0 {
		return NoteCert{}, fmt.Errorf("%w: cert fields: %w", ErrMalformed, errors.Join(errs...))
	}

	copy(c.NoteID[:], noteID)
	copy(c.WalletID[:], walletID)
	c.IssuedAt = int64(issued)   //nolint:gosec // fce.Uint64 guarantees <= MaxInt64
	c.ExpiresAt = int64(expires) //nolint:gosec // fce.Uint64 guarantees <= MaxInt64
	c.Enforcement = Enforcement(enf)
	if c.Enforcement > EnforcementStrongBox || c.ExpiresAt <= c.IssuedAt || c.DenominationPaise == 0 {
		return NoteCert{}, fmt.Errorf("%w: cert semantics", ErrMalformed)
	}
	if c.PublicKey, err = sig.ParseP256PublicKey(spki); err != nil {
		return NoteCert{}, fmt.Errorf("%w: note key: %w", ErrMalformed, err)
	}
	return c, nil
}

// VerifyIssuer checks the issuer signature over the certificate body.
func (s SignedNoteCert) VerifyIssuer(keys IssuerKeys) error {
	pub, ok := keys.Lookup(s.Cert.IssuerKeyID)
	if !ok {
		return ErrUnknownIssuer
	}
	if err := sig.Verify(pub, domains.NoteCert, s.Body, s.Signature); err != nil {
		return ErrIssuerSignature
	}
	return nil
}
