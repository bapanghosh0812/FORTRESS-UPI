// Package devicesig enforces cryptographic device binding on every API call.
//
// Each request carries an ECDSA-P256 signature, produced inside the device's
// hardware keystore, over an FCE-encoded canonical request:
//
//	{1 method, 2 escaped path, 3 raw query, 4 SHA-256(body), 5 timestamp ms, 6 nonce, 7 device id}
//
// Verification order: header syntax → clock skew → device lookup → signature
// → nonce (single use). Nonces are consumed only after the signature verifies,
// so unauthenticated callers cannot exhaust the nonce store.
// Kotlin twin: core/security/binding/RequestSigner.kt.
package devicesig

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// Header names.
const (
	HeaderDevice    = "X-Fortress-Device"
	HeaderTimestamp = "X-Fortress-Timestamp"
	HeaderNonce     = "X-Fortress-Nonce"
	HeaderSignature = "X-Fortress-Signature"
	// HeaderVerified is set for upstreams after successful verification. Any
	// client-supplied value is always stripped first.
	HeaderVerified = "X-Fortress-Verified-Device"
)

const (
	tagMethod uint8 = iota + 1
	tagPath
	tagQuery
	tagBodyHash
	tagTimestamp
	tagNonce
	tagDevice
)

var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)

// CanonicalRequest builds the signed request body.
func CanonicalRequest(method, escapedPath, rawQuery string, bodyHash [32]byte,
	tsMillis uint64, nonce []byte, deviceID string) ([]byte, error) {
	return fce.NewEncoder().
		String(tagMethod, method).
		String(tagPath, escapedPath).
		String(tagQuery, rawQuery).
		Bytes(tagBodyHash, bodyHash[:]).
		Uint64(tagTimestamp, tsMillis).
		Bytes(tagNonce, nonce).
		String(tagDevice, deviceID).
		Finish()
}

// Device is a bound device record.
type Device struct {
	ID        string
	PublicKey *ecdsa.PublicKey
	Active    bool // false after SIM change, user logout, fraud hold or re-binding
}

// Registry resolves device bindings.
type Registry interface {
	Lookup(ctx context.Context, id string) (Device, bool, error)
}

// NonceStore records single-use nonces. Production: Redis SET key 1 NX PX ttl.
type NonceStore interface {
	Remember(ctx context.Context, key string, ttl time.Duration) (fresh bool, err error)
}

// Verifier is the HTTP middleware factory.
type Verifier struct {
	reg     Registry
	nonces  NonceStore
	now     func() time.Time
	skew    time.Duration
	maxBody int64
	log     *slog.Logger
}

// NewVerifier returns a verifier with a ±30 s clock-skew window and 64 KiB body cap.
func NewVerifier(reg Registry, nonces NonceStore, now func() time.Time, log *slog.Logger) *Verifier {
	return &Verifier{reg: reg, nonces: nonces, now: now, skew: 30 * time.Second, maxBody: fce.MaxMessageSize, log: log}
}

// Middleware verifies the request before passing it to next.
func (v *Verifier) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Header.Del(HeaderVerified)
		deviceID, reason := v.verify(w, r)
		if reason != "" {
			// Single opaque response for every failure: no verification oracle.
			v.log.InfoContext(r.Context(), "request rejected", "reason", reason, "path", r.URL.Path)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		r.Header.Set(HeaderVerified, deviceID)
		r.Header.Del(HeaderSignature)
		next.ServeHTTP(w, r)
	})
}

// verify returns the device ID, or a non-empty internal reason on failure.
func (v *Verifier) verify(w http.ResponseWriter, r *http.Request) (string, string) {
	deviceID := r.Header.Get(HeaderDevice)
	if !deviceIDPattern.MatchString(deviceID) {
		return "", "device_header"
	}
	ts, err := strconv.ParseUint(r.Header.Get(HeaderTimestamp), 10, 63)
	if err != nil {
		return "", "timestamp_header"
	}
	nonce, err := base64.RawURLEncoding.DecodeString(r.Header.Get(HeaderNonce))
	if err != nil || len(nonce) < 16 || len(nonce) > 32 {
		return "", "nonce_header"
	}
	signature, err := base64.RawURLEncoding.DecodeString(r.Header.Get(HeaderSignature))
	if err != nil || len(signature) < 8 || len(signature) > 80 {
		return "", "signature_header"
	}

	now := v.now()
	sent := time.UnixMilli(int64(ts)) //nolint:gosec // ParseUint bitSize 63 guarantees range
	if sent.Before(now.Add(-v.skew)) || sent.After(now.Add(v.skew)) {
		return "", "clock_skew"
	}

	dev, ok, err := v.reg.Lookup(r.Context(), deviceID)
	if err != nil {
		return "", "registry_error"
	}
	if !ok || !dev.Active {
		return "", "unknown_or_inactive_device"
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, v.maxBody))
	if err != nil {
		return "", "body"
	}
	r.Body = io.NopCloser(bytes.NewReader(body))

	canonical, err := CanonicalRequest(r.Method, r.URL.EscapedPath(), r.URL.RawQuery,
		sha256.Sum256(body), ts, nonce, deviceID)
	if err != nil {
		return "", "canonical"
	}
	if err := sig.Verify(dev.PublicKey, domains.Request, canonical, signature); err != nil {
		return "", "signature"
	}

	fresh, err := v.nonces.Remember(r.Context(), deviceID+"|"+base64.RawURLEncoding.EncodeToString(nonce), 2*v.skew)
	if err != nil {
		return "", "nonce_store"
	}
	if !fresh {
		return "", "replay"
	}
	return deviceID, ""
}
