// Package attest verifies Android Keystore key attestation certificate chains.
//
// A device proves a key lives in secure hardware by returning, alongside the
// public key, an X.509 chain whose leaf carries the KeyDescription extension
// (OID 1.3.6.1.4.1.11129.2.1.17) and which chains to Google's attestation
// roots. The verifier checks the chain signatures against pinned roots, binds
// the leaf key to the key the client claims, matches the server challenge,
// and extracts the authorizations Fortress policy depends on.
//
// Reference: https://source.android.com/docs/security/features/keystore/attestation
package attest

import (
	"bytes"
	"crypto/subtle"
	"crypto/x509"
	"encoding/asn1"
	"errors"
	"fmt"
)

// OIDKeyDescription is the Android key attestation extension.
var OIDKeyDescription = asn1.ObjectIdentifier{1, 3, 6, 1, 4, 1, 11129, 2, 1, 17}

// SecurityLevel of the attesting environment.
type SecurityLevel int

const (
	LevelSoftware  SecurityLevel = 0
	LevelTEE       SecurityLevel = 1
	LevelStrongBox SecurityLevel = 2
)

// BootState is the verified-boot state from RootOfTrust.
type BootState int

const (
	BootVerified   BootState = 0
	BootSelfSigned BootState = 1
	BootUnverified BootState = 2
	BootFailed     BootState = 3
)

// Authorization tags used by policy (KeyMint Tag numbers).
const (
	tagRollbackResistance       = 303
	tagUsageCountLimit          = 405
	tagUnlockedDeviceRequired   = 509
	tagRootOfTrust              = 704
	tagOSPatchLevel             = 706
	tagAttestationApplicationID = 709
)

// Errors are coarse by design; callers log the wrapped detail server-side.
var (
	ErrChain       = errors.New("attest: certificate chain invalid")
	ErrUntrusted   = errors.New("attest: chain does not end at a trusted root")
	ErrExtension   = errors.New("attest: key description missing or malformed")
	ErrChallenge   = errors.New("attest: challenge mismatch")
	ErrKeyMismatch = errors.New("attest: attested key differs from presented key")
	ErrPolicy      = errors.New("attest: policy violation")
)

// Result is what the verifier extracted from a valid attestation.
type Result struct {
	SecurityLevel          SecurityLevel
	BootState              BootState
	DeviceLocked           bool
	HardwareUsageLimit     int // 0 = no hardware-enforced limit
	RollbackResistant      bool
	UnlockedDeviceRequired bool
	OSPatchLevel           int
	PackageNames           []string
	SignatureDigests       [][]byte
}

// Policy configures what a verification accepts.
type Policy struct {
	// Roots are trusted attestation root public keys (SPKI DER), e.g. Google's
	// hardware attestation roots, loaded from configuration — never fetched at runtime.
	Roots [][]byte
	// PackageName and SignerDigests (SHA-256 of the signing cert) pin the app.
	PackageName   string
	SignerDigests [][]byte
	MinLevel      SecurityLevel
	// RequireLockedVerifiedBoot rejects unlocked bootloaders and custom ROMs.
	RequireLockedVerifiedBoot bool
}

type keyDescription struct {
	AttestationVersion       int
	AttestationSecurityLevel asn1.Enumerated
	KeyMintVersion           int
	KeyMintSecurityLevel     asn1.Enumerated
	AttestationChallenge     []byte
	UniqueID                 []byte
	SoftwareEnforced         asn1.RawValue
	HardwareEnforced         asn1.RawValue
}

type rootOfTrust struct {
	VerifiedBootKey   []byte
	DeviceLocked      bool
	VerifiedBootState asn1.Enumerated
	VerifiedBootHash  []byte `asn1:"optional"`
}

type packageInfo struct {
	Name    []byte
	Version int64
}

type applicationID struct {
	Packages   []packageInfo `asn1:"set"`
	Signatures [][]byte      `asn1:"set"`
}

// Verify checks chain (leaf first, DER) against policy, binds it to
// presentedSPKI, and requires the attestation challenge to equal challenge.
func Verify(chain [][]byte, presentedSPKI, challenge []byte, p Policy) (*Result, error) {
	if len(chain) < 2 || len(chain) > 10 {
		return nil, fmt.Errorf("%w: length %d", ErrChain, len(chain))
	}
	certs := make([]*x509.Certificate, len(chain))
	for i, der := range chain {
		c, err := x509.ParseCertificate(der)
		if err != nil {
			return nil, fmt.Errorf("%w: cert %d: %w", ErrChain, i, err)
		}
		certs[i] = c
	}
	// Signature chain. Validity dates are not enforced: attestation certs for
	// factory-provisioned keys legitimately carry fixed or epoch dates.
	for i := 0; i < len(certs)-1; i++ {
		if err := certs[i].CheckSignatureFrom(certs[i+1]); err != nil {
			return nil, fmt.Errorf("%w: link %d: %w", ErrChain, i, err)
		}
	}
	root := certs[len(certs)-1]
	if err := root.CheckSignatureFrom(root); err != nil {
		return nil, fmt.Errorf("%w: root not self-signed: %w", ErrChain, err)
	}
	trusted := false
	for _, r := range p.Roots {
		if bytes.Equal(r, root.RawSubjectPublicKeyInfo) {
			trusted = true
			break
		}
	}
	if !trusted {
		return nil, ErrUntrusted
	}

	leaf := certs[0]
	if !bytes.Equal(leaf.RawSubjectPublicKeyInfo, presentedSPKI) {
		return nil, ErrKeyMismatch
	}
	var ext []byte
	for _, e := range leaf.Extensions {
		if e.Id.Equal(OIDKeyDescription) {
			ext = e.Value
			break
		}
	}
	if ext == nil {
		return nil, ErrExtension
	}
	var kd keyDescription
	if rest, err := asn1.Unmarshal(ext, &kd); err != nil || len(rest) != 0 {
		return nil, fmt.Errorf("%w: %v", ErrExtension, err)
	}
	if subtle.ConstantTimeCompare(kd.AttestationChallenge, challenge) != 1 {
		return nil, ErrChallenge
	}

	res := &Result{SecurityLevel: SecurityLevel(kd.AttestationSecurityLevel)}
	hw, err := parseAuthorizations(kd.HardwareEnforced.FullBytes)
	if err != nil {
		return nil, err
	}
	sw, err := parseAuthorizations(kd.SoftwareEnforced.FullBytes)
	if err != nil {
		return nil, err
	}
	if err := hw.applyHardware(res); err != nil {
		return nil, err
	}
	if raw, ok := sw[tagAttestationApplicationID]; ok {
		if err := applyAppID(raw, res); err != nil {
			return nil, err
		}
	}
	if err := p.check(res); err != nil {
		return nil, err
	}
	return res, nil
}

func (p Policy) check(r *Result) error {
	if r.SecurityLevel < p.MinLevel || r.SecurityLevel > LevelStrongBox {
		return fmt.Errorf("%w: security level %d", ErrPolicy, r.SecurityLevel)
	}
	if p.RequireLockedVerifiedBoot && (r.BootState != BootVerified || !r.DeviceLocked) {
		return fmt.Errorf("%w: boot state %d locked=%v", ErrPolicy, r.BootState, r.DeviceLocked)
	}
	if p.PackageName != "" {
		found := false
		for _, n := range r.PackageNames {
			if n == p.PackageName {
				found = true
			}
		}
		if !found {
			return fmt.Errorf("%w: package", ErrPolicy)
		}
	}
	if len(p.SignerDigests) > 0 {
		ok := false
		for _, want := range p.SignerDigests {
			for _, got := range r.SignatureDigests {
				if subtle.ConstantTimeCompare(want, got) == 1 {
					ok = true
				}
			}
		}
		if !ok {
			return fmt.Errorf("%w: signer", ErrPolicy)
		}
	}
	return nil
}

type authorizations map[int][]byte // tag → inner (EXPLICIT) TLV bytes

// parseAuthorizations walks an AuthorizationList element by element, so
// unknown or future tags never derail parsing of the ones policy needs.
func parseAuthorizations(full []byte) (authorizations, error) {
	var items []asn1.RawValue
	if rest, err := asn1.Unmarshal(full, &items); err != nil || len(rest) != 0 {
		return nil, fmt.Errorf("%w: authorization list: %v", ErrExtension, err)
	}
	out := make(authorizations, len(items))
	for _, it := range items {
		if it.Class != asn1.ClassContextSpecific {
			return nil, fmt.Errorf("%w: unexpected class %d", ErrExtension, it.Class)
		}
		out[it.Tag] = it.Bytes
	}
	return out, nil
}

func (a authorizations) applyHardware(r *Result) error {
	if raw, ok := a[tagRootOfTrust]; ok {
		var rot rootOfTrust
		if rest, err := asn1.Unmarshal(raw, &rot); err != nil || len(rest) != 0 {
			return fmt.Errorf("%w: root of trust: %v", ErrExtension, err)
		}
		r.BootState = BootState(rot.VerifiedBootState)
		r.DeviceLocked = rot.DeviceLocked
	} else {
		r.BootState = BootUnverified
	}
	if raw, ok := a[tagUsageCountLimit]; ok {
		var n int
		if _, err := asn1.Unmarshal(raw, &n); err != nil {
			return fmt.Errorf("%w: usage count: %v", ErrExtension, err)
		}
		r.HardwareUsageLimit = n
	}
	if raw, ok := a[tagOSPatchLevel]; ok {
		_, _ = asn1.Unmarshal(raw, &r.OSPatchLevel)
	}
	_, r.RollbackResistant = a[tagRollbackResistance]
	_, r.UnlockedDeviceRequired = a[tagUnlockedDeviceRequired]
	return nil
}

func applyAppID(explicit []byte, r *Result) error {
	// [709] EXPLICIT OCTET STRING whose content is a DER AttestationApplicationId.
	var octets []byte
	if _, err := asn1.Unmarshal(explicit, &octets); err != nil {
		return fmt.Errorf("%w: application id wrapper: %v", ErrExtension, err)
	}
	var id applicationID
	if _, err := asn1.Unmarshal(octets, &id); err != nil {
		return fmt.Errorf("%w: application id: %v", ErrExtension, err)
	}
	for _, p := range id.Packages {
		r.PackageNames = append(r.PackageNames, string(p.Name))
	}
	r.SignatureDigests = id.Signatures
	return nil
}
