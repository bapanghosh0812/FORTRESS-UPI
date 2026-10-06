package attest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"errors"
	"math/big"
	"testing"
	"time"
)

// Test-only mirrors of the KeyMint ASN.1 structures, used to mint realistic chains.
type tRoot struct {
	Key    []byte
	Locked bool
	State  asn1.Enumerated
}

type tHW struct {
	UsageCountLimit int   `asn1:"explicit,tag:405,optional"`
	RootOfTrust     tRoot `asn1:"explicit,tag:704,optional"`
}

type tSW struct {
	AppID []byte `asn1:"explicit,tag:709,optional"`
}

type tKD struct {
	Version   int
	Level     asn1.Enumerated
	KMVersion int
	KMLevel   asn1.Enumerated
	Challenge []byte
	UniqueID  []byte
	SW        tSW
	HW        tHW
}

type chainSpec struct {
	level     asn1.Enumerated
	usage     int
	boot      asn1.Enumerated
	locked    bool
	challenge []byte
	pkg       string
}

var signerDigest = sha256.Sum256([]byte("fortress-release-cert"))

func mustKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func mint(t *testing.T, s chainSpec) (chain [][]byte, leafSPKI, rootSPKI []byte) {
	t.Helper()
	rootKey, leafKey := mustKey(t), mustKey(t)
	rootTpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Test Attestation Root"},
		NotBefore: time.Unix(0, 0), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign,
	}
	rootDER, err := x509.CreateCertificate(rand.Reader, rootTpl, rootTpl, &rootKey.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	root, _ := x509.ParseCertificate(rootDER)

	appID, err := asn1.Marshal(applicationID{
		Packages:   []packageInfo{{Name: []byte(s.pkg), Version: 1}},
		Signatures: [][]byte{signerDigest[:]},
	})
	if err != nil {
		t.Fatal(err)
	}
	kd, err := asn1.Marshal(tKD{
		Version: 300, Level: s.level, KMVersion: 300, KMLevel: s.level,
		Challenge: s.challenge, UniqueID: []byte{},
		SW: tSW{AppID: appID},
		HW: tHW{UsageCountLimit: s.usage, RootOfTrust: tRoot{Key: make([]byte, 32), Locked: s.locked, State: s.boot}},
	})
	if err != nil {
		t.Fatal(err)
	}
	leafTpl := &x509.Certificate{
		SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "Android Keystore Key"},
		NotBefore: time.Unix(0, 0), NotAfter: time.Now().Add(time.Hour),
		ExtraExtensions: []pkix.Extension{{Id: OIDKeyDescription, Value: kd}},
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTpl, root, &leafKey.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(leafDER)
	return [][]byte{leafDER, rootDER}, leaf.RawSubjectPublicKeyInfo, root.RawSubjectPublicKeyInfo
}

func good() chainSpec {
	return chainSpec{level: 2, usage: 1, boot: 0, locked: true, challenge: []byte("server-challenge-0001"), pkg: "com.fortress.upi"}
}

func policy(root []byte) Policy {
	return Policy{
		Roots: [][]byte{root}, PackageName: "com.fortress.upi", SignerDigests: [][]byte{signerDigest[:]},
		MinLevel: LevelTEE, RequireLockedVerifiedBoot: true,
	}
}

func TestValidStrongBoxSingleUseKey(t *testing.T) {
	chain, leaf, root := mint(t, good())
	res, err := Verify(chain, leaf, []byte("server-challenge-0001"), policy(root))
	if err != nil {
		t.Fatalf("valid chain rejected: %v", err)
	}
	if res.SecurityLevel != LevelStrongBox || res.HardwareUsageLimit != 1 || !res.DeviceLocked || res.BootState != BootVerified {
		t.Fatalf("unexpected result %+v", res)
	}
}

func TestRejections(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*chainSpec)
		check  func(chain [][]byte, leaf, root []byte) error
		want   error
	}{
		{"wrong challenge", nil, func(c [][]byte, l, r []byte) error {
			_, err := Verify(c, l, []byte("replayed-challenge"), policy(r))
			return err
		}, ErrChallenge},
		{"untrusted root", nil, func(c [][]byte, l, _ []byte) error {
			_, _, otherRoot := mint(t, good())
			_, err := Verify(c, l, []byte("server-challenge-0001"), policy(otherRoot))
			return err
		}, ErrUntrusted},
		{"presented key differs", nil, func(c [][]byte, _, r []byte) error {
			_, other, _ := mint(t, good())
			_, err := Verify(c, other, []byte("server-challenge-0001"), policy(r))
			return err
		}, ErrKeyMismatch},
		{"unlocked bootloader", func(s *chainSpec) { s.boot, s.locked = 2, false }, nil, ErrPolicy},
		{"software keystore", func(s *chainSpec) { s.level = 0 }, nil, ErrPolicy},
		{"repackaged app", func(s *chainSpec) { s.pkg = "com.evil.clone" }, nil, ErrPolicy},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := good()
			if tc.mutate != nil {
				tc.mutate(&s)
			}
			chain, leaf, root := mint(t, s)
			var err error
			if tc.check != nil {
				err = tc.check(chain, leaf, root)
			} else {
				_, err = Verify(chain, leaf, s.challenge, policy(root))
			}
			if !errors.Is(err, tc.want) {
				t.Fatalf("got %v, want %v", err, tc.want)
			}
		})
	}
}

func TestBrokenChainRejected(t *testing.T) {
	chain, leaf, root := mint(t, good())
	other, _, _ := mint(t, good())
	if _, err := Verify([][]byte{chain[0], other[1]}, leaf, []byte("server-challenge-0001"), policy(root)); !errors.Is(err, ErrChain) {
		t.Fatalf("got %v", err)
	}
}
