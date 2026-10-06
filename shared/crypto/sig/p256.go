// Package sig provides the only signature primitives Fortress services use:
// ECDSA over NIST P-256 with SHA-256, DER-encoded — the native format of
// Android Keystore/StrongBox, Apple Secure Enclave and PKCS#11 HSMs.
package sig

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"fmt"

	"github.com/fortress-upi/fortress/shared/crypto/fce"
)

// Errors are intentionally generic.
var (
	ErrUnsupportedKey = errors.New("sig: unsupported public key")
	ErrBadSignature   = errors.New("sig: signature verification failed")
)

// ParseP256PublicKey parses an X.509 SubjectPublicKeyInfo (DER) and accepts
// only P-256 keys. Point validity is enforced by crypto/x509.
func ParseP256PublicKey(spki []byte) (*ecdsa.PublicKey, error) {
	pub, err := x509.ParsePKIXPublicKey(spki)
	if err != nil {
		return nil, fmt.Errorf("sig: parse spki: %w", err)
	}
	k, ok := pub.(*ecdsa.PublicKey)
	if !ok || k.Curve != elliptic.P256() {
		return nil, ErrUnsupportedKey
	}
	return k, nil
}

// Digest returns SHA-256(SigningInput(domain, body)).
func Digest(domain string, body []byte) [32]byte {
	return sha256.Sum256(fce.SigningInput(domain, body))
}

// Verify checks a DER ECDSA signature over the domain-separated body.
func Verify(pub *ecdsa.PublicKey, domain string, body, sigDER []byte) error {
	if pub == nil {
		return ErrUnsupportedKey
	}
	d := Digest(domain, body)
	if !ecdsa.VerifyASN1(pub, d[:], sigDER) {
		return ErrBadSignature
	}
	return nil
}

// Sign produces a DER ECDSA signature. Production issuer keys never live in
// process memory: signing goes through the HSM adapter. This function exists
// for tests, fixtures and local development only.
func Sign(priv *ecdsa.PrivateKey, domain string, body []byte) ([]byte, error) {
	d := Digest(domain, body)
	return ecdsa.SignASN1(rand.Reader, priv, d[:])
}

// MarshalPublicKey returns the SPKI DER encoding of pub.
func MarshalPublicKey(pub *ecdsa.PublicKey) ([]byte, error) {
	return x509.MarshalPKIXPublicKey(pub)
}

// GenerateP256 creates a P-256 key (tests/fixtures only).
func GenerateP256() (*ecdsa.PrivateKey, error) {
	return ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
}
