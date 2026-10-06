package app

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"sync"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/attest"
	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/signer"
)

// Ledger moves money from the user's bank account into the offline pool.
// Implementations must be idempotent on idempotencyKey.
type Ledger interface {
	DebitToOfflinePool(ctx context.Context, deviceID, accountRef string, walletID domain.ID16, amountPaise uint64, idempotencyKey string) error
}

// NoteRequest is one attested key the device wants certified.
type NoteRequest struct {
	KeyAlias          string
	DenominationPaise uint64
	PublicKey         []byte
	AttestationChain  [][]byte
}

// IssuedNote is a certified note returned to the device.
type IssuedNote struct {
	KeyAlias    string
	Certificate []byte
}

// IssueConfig is issuance policy (regulatory caps live here, not on devices).
type IssueConfig struct {
	Attestation   attest.Policy
	NoteTTL       time.Duration
	ChallengeTTL  time.Duration
	MaxVaultPaise uint64
	Denominations map[uint64]bool
}

// Issuance errors.
var (
	ErrIssueInvalid = errors.New("invalid_issuance")
	ErrChallenge    = errors.New("challenge_invalid")
	ErrAttestation  = errors.New("attestation_failed")
)

var aliasPattern = regexp.MustCompile(`^fortress\.note\.[0-9a-f]{32}$`)

type challengeState struct {
	deviceID    string
	expires     time.Time
	used        bool
	fingerprint [32]byte
	result      []IssuedNote
}

// IssueService mints offline notes.
type IssueService struct {
	issuer signer.Signer
	ledger Ledger
	now    func() time.Time
	cfg    IssueConfig
	log    *slog.Logger

	mu         sync.Mutex
	challenges map[[32]byte]*challengeState
}

// NewIssueService wires issuance. Challenge state is in memory; production
// persists it (CockroachDB) so retries survive pod restarts.
func NewIssueService(issuer signer.Signer, ledger Ledger, now func() time.Time, cfg IssueConfig, log *slog.Logger) *IssueService {
	return &IssueService{issuer: issuer, ledger: ledger, now: now, cfg: cfg, log: log, challenges: map[[32]byte]*challengeState{}}
}

// Challenge returns a fresh single-use attestation challenge for deviceID.
func (s *IssueService) Challenge(_ context.Context, deviceID string) ([32]byte, error) {
	var c [32]byte
	if _, err := rand.Read(c[:]); err != nil {
		return c, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	for k, v := range s.challenges { // opportunistic sweep keeps the map bounded
		if now.After(v.expires.Add(time.Hour)) {
			delete(s.challenges, k)
		}
	}
	s.challenges[c] = &challengeState{deviceID: deviceID, expires: now.Add(s.cfg.ChallengeTTL)}
	return c, nil
}

// WalletID derives the device's offline wallet identifier.
func WalletID(deviceID string) domain.ID16 {
	h := sha256.Sum256(fce.SigningInput("FORTRESS/WALLET/v1", []byte(deviceID)))
	var id domain.ID16
	copy(id[:], h[:16])
	return id
}

// Issue verifies every attested key, debits the bank account once, and returns
// issuer-signed note certificates. A retry with the same challenge and the
// same keys returns the original certificates (no double debit).
func (s *IssueService) Issue(ctx context.Context, deviceID string, challenge [32]byte, accountRef string, notes []NoteRequest) ([]IssuedNote, error) {
	fp, total, err := s.validate(notes)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	st, ok := s.challenges[challenge]
	switch {
	case !ok || st.deviceID != deviceID:
		s.mu.Unlock()
		return nil, ErrChallenge
	case st.used && st.fingerprint == fp && st.result != nil:
		res := st.result
		s.mu.Unlock()
		return res, nil
	case st.used || s.now().After(st.expires):
		s.mu.Unlock()
		return nil, ErrChallenge
	}
	st.used, st.fingerprint = true, fp
	s.mu.Unlock()

	enforcement := make([]domain.Enforcement, len(notes))
	for i, n := range notes {
		res, err := attest.Verify(n.AttestationChain, n.PublicKey, challenge[:], s.cfg.Attestation)
		if err != nil {
			s.log.WarnContext(ctx, "note key attestation rejected", "device_id", deviceID, "err", err)
			return nil, ErrAttestation
		}
		if res.HardwareUsageLimit != 1 {
			return nil, fmt.Errorf("%w: single use not hardware-enforced", ErrAttestation)
		}
		enforcement[i] = domain.EnforcementTEE
		if res.SecurityLevel == attest.LevelStrongBox {
			enforcement[i] = domain.EnforcementStrongBox
		}
	}

	wallet := WalletID(deviceID)
	if err := s.ledger.DebitToOfflinePool(ctx, deviceID, accountRef, wallet, total, "iss-"+hex.EncodeToString(challenge[:16])); err != nil {
		return nil, fmt.Errorf("debit: %w", err)
	}

	now := s.now()
	out := make([]IssuedNote, 0, len(notes))
	for i, n := range notes {
		var noteID [16]byte
		if _, err := rand.Read(noteID[:]); err != nil {
			return nil, err
		}
		body, err := fce.NewEncoder().
			Bytes(1, noteID[:]).Bytes(2, wallet[:]).Bytes(3, n.PublicKey).
			Uint64(4, n.DenominationPaise).
			Uint64(5, uint64(now.Unix())).                     //nolint:gosec // wall clock is post-1970
			Uint64(6, uint64(now.Add(s.cfg.NoteTTL).Unix())). //nolint:gosec // wall clock is post-1970
			Bytes(7, s.issuer.KeyID()).Uint64(8, uint64(enforcement[i])).
			Finish()
		if err != nil {
			return nil, err
		}
		signature, err := s.issuer.Sign(ctx, domains.NoteCert, body)
		if err != nil {
			return nil, fmt.Errorf("issuer sign: %w", err)
		}
		env, err := fce.NewEncoder().Bytes(1, body).Bytes(2, signature).Finish()
		if err != nil {
			return nil, err
		}
		out = append(out, IssuedNote{KeyAlias: n.KeyAlias, Certificate: env})
	}

	s.mu.Lock()
	st.result = out
	s.mu.Unlock()
	s.log.InfoContext(ctx, "offline notes issued", "device_id", deviceID, "notes", len(out), "total_paise", total)
	return out, nil
}

func (s *IssueService) validate(notes []NoteRequest) ([32]byte, uint64, error) {
	var fp [32]byte
	if len(notes) == 0 || len(notes) > 32 {
		return fp, 0, fmt.Errorf("%w: note count", ErrIssueInvalid)
	}
	h := sha256.New()
	seen := make(map[string]bool, len(notes))
	var total uint64
	for _, n := range notes {
		if !aliasPattern.MatchString(n.KeyAlias) || seen[n.KeyAlias] {
			return fp, 0, fmt.Errorf("%w: key alias", ErrIssueInvalid)
		}
		seen[n.KeyAlias] = true
		if !s.cfg.Denominations[n.DenominationPaise] {
			return fp, 0, fmt.Errorf("%w: denomination", ErrIssueInvalid)
		}
		total += n.DenominationPaise
		h.Write([]byte(n.KeyAlias))
		h.Write(n.PublicKey)
		h.Write([]byte(fmt.Sprint(n.DenominationPaise)))
	}
	if total > s.cfg.MaxVaultPaise {
		return fp, 0, fmt.Errorf("%w: above vault cap", ErrIssueInvalid)
	}
	copy(fp[:], h.Sum(nil))
	return fp, total, nil
}
