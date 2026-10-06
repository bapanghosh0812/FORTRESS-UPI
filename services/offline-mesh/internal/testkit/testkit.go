// Package testkit builds valid, fully signed offline-protocol artifacts for
// tests, benchmarks and CI load fixtures. Production issuer keys live in the
// HSM; nothing here is used on a production code path.
package testkit

import (
	"crypto/ecdsa"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// Issuer simulates the HSM-backed note issuer.
type Issuer struct {
	KeyID []byte
	Priv  *ecdsa.PrivateKey
}

// NewIssuer creates an issuer with a random key ID.
func NewIssuer() (*Issuer, error) {
	k, err := sig.GenerateP256()
	if err != nil {
		return nil, err
	}
	id := make([]byte, 8)
	if _, err := rand.Read(id); err != nil {
		return nil, err
	}
	return &Issuer{KeyID: id, Priv: k}, nil
}

// Keyring returns the public keyring for this issuer.
func (i *Issuer) Keyring() domain.StaticIssuerKeys {
	return domain.StaticIssuerKeys{hex.EncodeToString(i.KeyID): &i.Priv.PublicKey}
}

// Note is an issued note with its (simulated single-use) private key.
type Note struct {
	ID     domain.ID16
	Wallet domain.ID16
	Denom  uint64
	Priv   *ecdsa.PrivateKey
	Signed []byte // envelope bytes
}

// RandomID returns a random 128-bit identifier.
func RandomID() domain.ID16 {
	var id domain.ID16
	if _, err := rand.Read(id[:]); err != nil {
		panic(fmt.Sprintf("crypto/rand failed: %v", err)) // unrecoverable platform failure
	}
	return id
}

// IssueNote creates and certifies a StrongBox-class note.
func (i *Issuer) IssueNote(wallet domain.ID16, denom uint64, issued, expires time.Time) (*Note, error) {
	k, err := sig.GenerateP256()
	if err != nil {
		return nil, err
	}
	spki, err := sig.MarshalPublicKey(&k.PublicKey)
	if err != nil {
		return nil, err
	}
	id := RandomID()
	body, err := fce.NewEncoder().
		Bytes(1, id[:]).
		Bytes(2, wallet[:]).
		Bytes(3, spki).
		Uint64(4, denom).
		Uint64(5, uint64(issued.Unix())).  //nolint:gosec // test clock is post-1970
		Uint64(6, uint64(expires.Unix())). //nolint:gosec // test clock is post-1970
		Bytes(7, i.KeyID).
		Uint64(8, uint64(domain.EnforcementStrongBox)).
		Finish()
	if err != nil {
		return nil, err
	}
	s, err := sig.Sign(i.Priv, domains.NoteCert, body)
	if err != nil {
		return nil, err
	}
	env, err := fce.NewEncoder().Bytes(1, body).Bytes(2, s).Finish()
	if err != nil {
		return nil, err
	}
	return &Note{ID: id, Wallet: wallet, Denom: denom, Priv: k, Signed: env}, nil
}

// BundleSpec describes a payment to build.
type BundleSpec struct {
	PaymentID  domain.ID16
	ReceiverID domain.ID16
	Transcript [32]byte
	CreatedAt  uint64
	Notes      []*Note
	Applied    []uint64
}

// BuildBundle signs every spend with its note key and encodes the bundle.
func BuildBundle(s BundleSpec) ([]byte, error) {
	if len(s.Notes) != len(s.Applied) {
		return nil, fmt.Errorf("testkit: %d notes but %d amounts", len(s.Notes), len(s.Applied))
	}
	var total uint64
	for _, a := range s.Applied {
		total += a
	}
	items := make([][]byte, 0, len(s.Notes))
	for idx, n := range s.Notes {
		body, err := domain.SpendSigBody(n.ID, s.PaymentID, s.ReceiverID, s.Transcript, s.Applied[idx], total, s.CreatedAt)
		if err != nil {
			return nil, err
		}
		spendSig, err := sig.Sign(n.Priv, domains.Spend, body)
		if err != nil {
			return nil, err
		}
		item, err := fce.NewEncoder().Bytes(1, n.Signed).Uint64(2, s.Applied[idx]).Bytes(3, spendSig).Finish()
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return fce.NewEncoder().
		Bytes(1, s.PaymentID[:]).
		Bytes(2, s.Transcript[:]).
		Bytes(3, s.ReceiverID[:]).
		Uint64(4, total).
		Uint64(5, s.CreatedAt).
		List(6, items).
		Finish()
}
