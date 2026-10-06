package signer

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"os"
	"path/filepath"
	"testing"

	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

func TestGenerateSignVerify(t *testing.T) {
	s, err := Generate([]byte{1, 2})
	if err != nil {
		t.Fatal(err)
	}
	signature, err := s.Sign(context.Background(), "FORTRESS/X/v1", []byte("body"))
	if err != nil {
		t.Fatal(err)
	}
	if err := sig.Verify(s.Public(), "FORTRESS/X/v1", []byte("body"), signature); err != nil {
		t.Fatalf("verify: %v", err)
	}
	if s.Hardware() {
		t.Fatal("software signer must not claim hardware")
	}
}

func TestLoadSoftwarePEM(t *testing.T) {
	k, err := sig.GenerateP256()
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "issuer.pem")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := LoadSoftware(path, []byte{9})
	if err != nil {
		t.Fatal(err)
	}
	if !s.Public().Equal(&k.PublicKey) {
		t.Fatal("loaded a different key")
	}
	if _, err := NewSoftware(nil, k); err == nil {
		t.Fatal("empty key id accepted")
	}
}
