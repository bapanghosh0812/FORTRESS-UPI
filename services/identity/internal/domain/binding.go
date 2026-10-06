// Package domain models device binding: SMS-proved SIM possession plus
// hardware-attested keys, producing a bound Device and its receiver certificate.
package domain

import (
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"time"

	"github.com/fortress-upi/fortress/shared/crypto/fce"
)

// Session is one binding attempt.
type Session struct {
	ID        [16]byte
	Challenge [32]byte
	SMSToken  string
	CreatedAt time.Time
	ExpiresAt time.Time
	MSISDN    string // set when the operator gateway delivers the SMS
	Completed bool
}

// Device is a bound handset.
type Device struct {
	ID             string
	IdentitySPKI   []byte
	RequestSPKI    []byte
	MSISDN         string
	SIMFingerprint [32]byte
	StrongBox      bool
	ReceiverID     [16]byte
	Active         bool
	BoundAt        time.Time
}

// Payload is the FCE body the identity key signs (core/security DeviceBinding.kt).
type Payload struct {
	SessionID       [16]byte
	IdentitySPKI    []byte
	RequestSPKI     []byte
	SIMFingerprint  [32]byte
	TimestampMillis int64
}

// Errors.
var (
	ErrInvalid     = errors.New("invalid_request")
	ErrSession     = errors.New("unknown_session")
	ErrExpired     = errors.New("session_expired")
	ErrReplay      = errors.New("session_used")
	ErrSMSPending  = errors.New("sms_not_received")
	ErrAttestation = errors.New("attestation_failed")
	ErrIntegrity   = errors.New("integrity_failed")
)

// ParsePayload strictly decodes the signed binding payload.
func ParsePayload(b []byte) (Payload, error) {
	m, err := fce.Decode(b, 1, 2, 3, 4, 5)
	if err != nil {
		return Payload{}, fmt.Errorf("%w: %w", ErrInvalid, err)
	}
	sid, err1 := m.Fixed(1, 16)
	idk, err2 := m.BytesRange(2, 1, 256)
	rqk, err3 := m.BytesRange(3, 1, 256)
	sim, err4 := m.Fixed(4, 32)
	ts, err5 := m.Uint64(5)
	if err := errors.Join(err1, err2, err3, err4, err5); err != nil {
		return Payload{}, fmt.Errorf("%w: %w", ErrInvalid, err)
	}
	var p Payload
	copy(p.SessionID[:], sid)
	copy(p.SIMFingerprint[:], sim)
	p.IdentitySPKI, p.RequestSPKI = idk, rqk
	p.TimestampMillis = int64(ts) //nolint:gosec // fce.Uint64 guarantees <= MaxInt64
	return p, nil
}

// ReceiverCertBody encodes a ReceiverCertificate body (tags match Wire.kt).
func ReceiverCertBody(receiverID [16]byte, displayName string, identitySPKI []byte, issued, expires time.Time, issuerKeyID []byte) ([]byte, error) {
	return fce.NewEncoder().
		Bytes(1, receiverID[:]).
		String(2, displayName).
		Bytes(3, identitySPKI).
		Uint64(4, uint64(issued.Unix())).  //nolint:gosec // wall clock is post-1970
		Uint64(5, uint64(expires.Unix())). //nolint:gosec // wall clock is post-1970
		Bytes(6, issuerKeyID).
		Finish()
}

// Envelope wraps a signed body as {1: body, 2: signature}.
func Envelope(body, signature []byte) ([]byte, error) {
	return fce.NewEncoder().Bytes(1, body).Bytes(2, signature).Finish()
}

var msisdnPattern = regexp.MustCompile(`^\+91[6-9]\d{9}$`)

// NormalizeMSISDN accepts Indian mobile numbers from the operator gateway.
func NormalizeMSISDN(raw string) (string, bool) {
	switch {
	case len(raw) == 10:
		raw = "+91" + raw
	case len(raw) == 12 && raw[:2] == "91":
		raw = "+" + raw
	}
	return raw, msisdnPattern.MatchString(raw)
}

// MaskMSISDN keeps only the last four digits.
func MaskMSISDN(m string) string {
	if len(m) < 4 {
		return "••••"
	}
	return "+91 ••••• •" + m[len(m)-4:]
}

// DeviceID formats a device identifier from random bytes.
func DeviceID(raw [16]byte) string { return "dev_" + hex.EncodeToString(raw[:]) }
