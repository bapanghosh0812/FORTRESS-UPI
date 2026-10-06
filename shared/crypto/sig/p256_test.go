package sig

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"errors"
	"testing"
)

func TestSignVerifyAndDomainSeparation(t *testing.T) {
	k, err := GenerateP256()
	if err != nil {
		t.Fatal(err)
	}
	s, err := Sign(k, "FORTRESS/A/v1", []byte("body"))
	if err != nil {
		t.Fatal(err)
	}
	if err := Verify(&k.PublicKey, "FORTRESS/A/v1", []byte("body"), s); err != nil {
		t.Fatalf("valid signature rejected: %v", err)
	}
	if err := Verify(&k.PublicKey, "FORTRESS/B/v1", []byte("body"), s); !errors.Is(err, ErrBadSignature) {
		t.Fatal("signature accepted under a different domain")
	}
	if err := Verify(&k.PublicKey, "FORTRESS/A/v1", []byte("bodY"), s); !errors.Is(err, ErrBadSignature) {
		t.Fatal("signature accepted for a different body")
	}
}

func TestParseRejectsOtherCurves(t *testing.T) {
	k, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := MarshalPublicKey(&k.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseP256PublicKey(der); !errors.Is(err, ErrUnsupportedKey) {
		t.Fatalf("P-384 key accepted: %v", err)
	}
}
