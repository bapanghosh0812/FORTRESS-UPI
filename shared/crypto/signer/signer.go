// Package signer abstracts issuer signing keys. Production keys live in the
// Thales Luna HSM and are reached through a PKCS#11 adapter implementing
// Signer; the software implementation exists for development, CI fixtures
// and staging, and services refuse it when FORTRESS_ENV=production.
package signer

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"

	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// Signer produces ECDSA-P256 DER signatures over domain-separated bodies.
type Signer interface {
	KeyID() []byte
	Public() *ecdsa.PublicKey
	Sign(ctx context.Context, domain string, body []byte) ([]byte, error)
	// Hardware reports whether the private key is non-exportable hardware.
	Hardware() bool
}

// Software holds a P-256 key in process memory (non-production only).
type Software struct {
	id  []byte
	key *ecdsa.PrivateKey
}

// NewSoftware wraps an in-memory key.
func NewSoftware(id []byte, key *ecdsa.PrivateKey) (*Software, error) {
	if key == nil || key.Curve != elliptic.P256() {
		return nil, errors.New("signer: P-256 key required")
	}
	if len(id) == 0 || len(id) > 32 {
		return nil, errors.New("signer: key id must be 1-32 bytes")
	}
	return &Software{id: append([]byte(nil), id...), key: key}, nil
}

// LoadSoftware reads a PEM (SEC 1 "EC PRIVATE KEY" or PKCS#8) P-256 key.
func LoadSoftware(path string, id []byte) (*Software, error) {
	raw, err := os.ReadFile(path) //nolint:gosec // path comes from operator configuration
	if err != nil {
		return nil, fmt.Errorf("signer: read key: %w", err)
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, errors.New("signer: no PEM block")
	}
	var key *ecdsa.PrivateKey
	switch block.Type {
	case "EC PRIVATE KEY":
		key, err = x509.ParseECPrivateKey(block.Bytes)
	case "PRIVATE KEY":
		var k any
		k, err = x509.ParsePKCS8PrivateKey(block.Bytes)
		if err == nil {
			var ok bool
			if key, ok = k.(*ecdsa.PrivateKey); !ok {
				err = errors.New("not an EC key")
			}
		}
	default:
		err = fmt.Errorf("unsupported PEM type %q", block.Type)
	}
	if err != nil {
		return nil, fmt.Errorf("signer: parse key: %w", err)
	}
	return NewSoftware(id, key)
}

// Generate creates an ephemeral key (tests and local development).
func Generate(id []byte) (*Software, error) {
	k, err := sig.GenerateP256()
	if err != nil {
		return nil, err
	}
	return NewSoftware(id, k)
}

// KeyID implements Signer.
func (s *Software) KeyID() []byte { return append([]byte(nil), s.id...) }

// Public implements Signer.
func (s *Software) Public() *ecdsa.PublicKey { return &s.key.PublicKey }

// Hardware implements Signer.
func (s *Software) Hardware() bool { return false }

// Sign implements Signer.
func (s *Software) Sign(_ context.Context, domain string, body []byte) ([]byte, error) {
	return sig.Sign(s.key, domain, body)
}
