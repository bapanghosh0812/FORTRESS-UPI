// Package attesttest mints Android-style key attestation chains for tests and
// local development. It is never linked into production binaries' code paths.
package attesttest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"math/big"
	"time"
)

type rootOfTrust struct {
	Key    []byte
	Locked bool
	State  asn1.Enumerated
}

type hardware struct {
	UsageCountLimit int         `asn1:"explicit,tag:405,optional"`
	RootOfTrust     rootOfTrust `asn1:"explicit,tag:704,optional"`
}

type software struct {
	AppID []byte `asn1:"explicit,tag:709,optional"`
}

type keyDescription struct {
	Version   int
	Level     asn1.Enumerated
	KMVersion int
	KMLevel   asn1.Enumerated
	Challenge []byte
	UniqueID  []byte
	SW        software
	HW        hardware
}

type packageInfo struct {
	Name    []byte
	Version int64
}

type applicationID struct {
	Packages   []packageInfo `asn1:"set"`
	Signatures [][]byte      `asn1:"set"`
}

// Options shape the attested properties.
type Options struct {
	Level        int // 0 software, 1 TEE, 2 StrongBox
	UsageLimit   int // 1 for single-use note keys
	Package      string
	SignerDigest []byte
}

// CA is a throwaway attestation root.
type CA struct {
	key  *ecdsa.PrivateKey
	cert *x509.Certificate
	der  []byte
}

// NewCA creates a self-signed attestation root.
func NewCA() (*CA, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Fortress Test Attestation Root"},
		NotBefore: time.Unix(0, 0), NotAfter: time.Now().Add(24 * time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		return nil, err
	}
	c, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, err
	}
	return &CA{key: k, cert: c, der: der}, nil
}

// RootSPKI returns the root public key for a verifier Policy.
func (c *CA) RootSPKI() []byte { return c.cert.RawSubjectPublicKeyInfo }

// Attest returns a chain (leaf first) attesting leaf with challenge.
func (c *CA) Attest(leaf *ecdsa.PublicKey, challenge []byte, o Options) ([][]byte, error) {
	appID, err := asn1.Marshal(applicationID{
		Packages:   []packageInfo{{Name: []byte(o.Package), Version: 1}},
		Signatures: [][]byte{o.SignerDigest},
	})
	if err != nil {
		return nil, err
	}
	kd, err := asn1.Marshal(keyDescription{
		Version: 300, Level: asn1.Enumerated(o.Level), KMVersion: 300, KMLevel: asn1.Enumerated(o.Level),
		Challenge: challenge, UniqueID: []byte{},
		SW: software{AppID: appID},
		HW: hardware{UsageCountLimit: o.UsageLimit, RootOfTrust: rootOfTrust{Key: make([]byte, 32), Locked: true, State: 0}},
	})
	if err != nil {
		return nil, err
	}
	serial, err := rand.Int(rand.Reader, big.NewInt(1<<62))
	if err != nil {
		return nil, err
	}
	tpl := &x509.Certificate{
		SerialNumber: serial, Subject: pkix.Name{CommonName: "Android Keystore Key"},
		NotBefore: time.Unix(0, 0), NotAfter: time.Now().Add(24 * time.Hour),
		ExtraExtensions: []pkix.Extension{{Id: asn1.ObjectIdentifier{1, 3, 6, 1, 4, 1, 11129, 2, 1, 17}, Value: kd}},
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, c.cert, leaf, c.key)
	if err != nil {
		return nil, err
	}
	return [][]byte{der, c.der}, nil
}
