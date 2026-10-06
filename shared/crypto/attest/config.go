package attest

import (
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"strings"
)

// LoadRootsPEM reads a PEM bundle of trusted attestation roots (for example
// Google's hardware attestation roots) and returns their SPKI DER bytes.
func LoadRootsPEM(path string) ([][]byte, error) {
	if path == "" {
		return nil, errors.New("attest: roots file path is empty")
	}
	raw, err := os.ReadFile(path) //nolint:gosec // operator-supplied configuration path
	if err != nil {
		return nil, fmt.Errorf("attest: read roots: %w", err)
	}
	var roots [][]byte
	for {
		var block *pem.Block
		block, raw = pem.Decode(raw)
		if block == nil {
			break
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("attest: parse root: %w", err)
		}
		roots = append(roots, c.RawSubjectPublicKeyInfo)
	}
	if len(roots) == 0 {
		return nil, errors.New("attest: no roots in bundle")
	}
	return roots, nil
}

// ParseDigests parses comma-separated hex SHA-256 digests (app signing certs).
func ParseDigests(spec string) ([][]byte, error) {
	var out [][]byte
	for _, h := range strings.Split(spec, ",") {
		if h = strings.TrimSpace(h); h == "" {
			continue
		}
		d, err := hex.DecodeString(h)
		if err != nil || len(d) != 32 {
			return nil, errors.New("attest: signer digests must be 64 hex chars each")
		}
		out = append(out, d)
	}
	if len(out) == 0 {
		return nil, errors.New("attest: at least one signer digest is required")
	}
	return out, nil
}
