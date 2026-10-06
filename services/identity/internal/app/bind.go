// Package app implements the device-binding use cases.
package app

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/fortress-upi/fortress/services/identity/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/attest"
	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
	"github.com/fortress-upi/fortress/shared/crypto/signer"
)

// Store persists sessions and devices. Production: CockroachDB with
// UNIQUE(sms_token) and a conditional UPDATE for CompleteSession.
type Store interface {
	CreateSession(ctx context.Context, s domain.Session) error
	SessionByID(ctx context.Context, id [16]byte) (domain.Session, bool, error)
	// AttachMSISDN records the SMS sender for an open session; false if unknown/expired.
	AttachMSISDN(ctx context.Context, token, msisdn string, now time.Time) (bool, error)
	// CompleteSession atomically marks a session used; false if it already was.
	CompleteSession(ctx context.Context, id [16]byte) (bool, error)
	// SaveDevice stores d and deactivates every other device bound to the same MSISDN.
	SaveDevice(ctx context.Context, d domain.Device) error
	Device(ctx context.Context, id string) (domain.Device, bool, error)
}

// IntegrityVerifier checks a Play Integrity token bound to requestHash.
// Production decodes it with Google's Play Integrity API and requires
// MEETS_STRONG_INTEGRITY (or DEVICE for low-risk tiers) + PLAY_RECOGNIZED.
type IntegrityVerifier interface {
	Verify(ctx context.Context, token string, requestHash [32]byte) error
}

// Config is binding policy.
type Config struct {
	VirtualNumber string
	SessionTTL    time.Duration
	SMSWait       time.Duration
	CertTTL       time.Duration
	MaxClockSkew  time.Duration
	Attestation   attest.Policy
	DisplayName   func(msisdn string) string
}

// Service binds devices.
type Service struct {
	store     Store
	integrity IntegrityVerifier
	issuer    signer.Signer
	now       func() time.Time
	cfg       Config
	log       *slog.Logger
}

// New wires the service.
func New(store Store, integrity IntegrityVerifier, issuer signer.Signer, now func() time.Time, cfg Config, log *slog.Logger) *Service {
	if cfg.DisplayName == nil {
		cfg.DisplayName = func(string) string { return "Fortress user" }
	}
	return &Service{store: store, integrity: integrity, issuer: issuer, now: now, cfg: cfg, log: log}
}

// BeginResult is returned to the device.
type BeginResult struct {
	Session       domain.Session
	VirtualNumber string
}

var tokenEncoding = base32.StdEncoding.WithPadding(base32.NoPadding)

// Begin opens a binding session with a fresh attestation challenge and SMS token.
func (s *Service) Begin(ctx context.Context) (BeginResult, error) {
	var sess domain.Session
	var tok [10]byte
	for _, b := range [][]byte{sess.ID[:], sess.Challenge[:], tok[:]} {
		if _, err := rand.Read(b); err != nil {
			return BeginResult{}, err
		}
	}
	now := s.now()
	sess.SMSToken = tokenEncoding.EncodeToString(tok[:])
	sess.CreatedAt, sess.ExpiresAt = now, now.Add(s.cfg.SessionTTL)
	if err := s.store.CreateSession(ctx, sess); err != nil {
		return BeginResult{}, err
	}
	return BeginResult{Session: sess, VirtualNumber: s.cfg.VirtualNumber}, nil
}

// ReceiveSMS handles the operator gateway callback ("FORTRESS <token>" from the bound SIM).
func (s *Service) ReceiveSMS(ctx context.Context, from, text string) error {
	msisdn, ok := domain.NormalizeMSISDN(strings.TrimSpace(from))
	if !ok {
		return fmt.Errorf("%w: sender", domain.ErrInvalid)
	}
	fields := strings.Fields(text)
	if len(fields) != 2 || !strings.EqualFold(fields[0], "FORTRESS") || len(fields[1]) != 16 {
		return fmt.Errorf("%w: body", domain.ErrInvalid)
	}
	ok, err := s.store.AttachMSISDN(ctx, strings.ToUpper(fields[1]), msisdn, s.now())
	if err != nil {
		return err
	}
	if !ok {
		return domain.ErrSession
	}
	return nil
}

// CompleteRequest is what the device sends after the SMS.
type CompleteRequest struct {
	Payload           []byte
	IdentitySignature []byte
	IdentityChain     [][]byte
	RequestChain      [][]byte
	IntegrityToken    string
	RiskReport        []byte
}

// CompleteResult is returned to the device.
type CompleteResult struct {
	DeviceID            string
	ReceiverCertificate []byte
	ReceiverID          [16]byte
}

// Complete verifies every proof and binds the device.
func (s *Service) Complete(ctx context.Context, req CompleteRequest) (*CompleteResult, error) {
	p, err := domain.ParsePayload(req.Payload)
	if err != nil {
		return nil, err
	}
	sess, ok, err := s.store.SessionByID(ctx, p.SessionID)
	switch {
	case err != nil:
		return nil, err
	case !ok:
		return nil, domain.ErrSession
	case sess.Completed:
		return nil, domain.ErrReplay
	case s.now().After(sess.ExpiresAt):
		return nil, domain.ErrExpired
	}
	if skew := s.now().Sub(time.UnixMilli(p.TimestampMillis)); skew > s.cfg.MaxClockSkew || skew < -s.cfg.MaxClockSkew {
		return nil, fmt.Errorf("%w: clock skew", domain.ErrInvalid)
	}

	// 1. Possession of the identity key.
	idPub, err := sig.ParseP256PublicKey(p.IdentitySPKI)
	if err != nil {
		return nil, fmt.Errorf("%w: identity key", domain.ErrInvalid)
	}
	if err := sig.Verify(idPub, domains.Binding, req.Payload, req.IdentitySignature); err != nil {
		return nil, fmt.Errorf("%w: identity signature", domain.ErrInvalid)
	}
	// 2. Both keys attested by genuine hardware, for this challenge, in our app.
	idRes, err := attest.Verify(req.IdentityChain, p.IdentitySPKI, sess.Challenge[:], s.cfg.Attestation)
	if err != nil {
		s.log.WarnContext(ctx, "identity attestation rejected", "err", err)
		return nil, domain.ErrAttestation
	}
	if _, err := attest.Verify(req.RequestChain, p.RequestSPKI, sess.Challenge[:], s.cfg.Attestation); err != nil {
		s.log.WarnContext(ctx, "request-key attestation rejected", "err", err)
		return nil, domain.ErrAttestation
	}
	// 3. Device + app integrity, bound to this exact payload.
	if err := s.integrity.Verify(ctx, req.IntegrityToken, sha256.Sum256(req.Payload)); err != nil {
		s.log.WarnContext(ctx, "integrity verdict rejected", "err", err)
		return nil, domain.ErrIntegrity
	}
	// 4. SIM possession: wait for the operator gateway to deliver the SMS.
	msisdn, err := s.awaitSMS(ctx, p.SessionID)
	if err != nil {
		return nil, err
	}
	if done, err := s.store.CompleteSession(ctx, p.SessionID); err != nil {
		return nil, err
	} else if !done {
		return nil, domain.ErrReplay
	}

	var rawID, receiverID [16]byte
	if _, err := rand.Read(rawID[:]); err != nil {
		return nil, err
	}
	if _, err := rand.Read(receiverID[:]); err != nil {
		return nil, err
	}
	now := s.now()
	body, err := domain.ReceiverCertBody(receiverID, s.cfg.DisplayName(msisdn), p.IdentitySPKI, now, now.Add(s.cfg.CertTTL), s.issuer.KeyID())
	if err != nil {
		return nil, err
	}
	signature, err := s.issuer.Sign(ctx, domains.ReceiverCert, body)
	if err != nil {
		return nil, fmt.Errorf("issuer sign: %w", err)
	}
	cert, err := domain.Envelope(body, signature)
	if err != nil {
		return nil, err
	}
	dev := domain.Device{
		ID: domain.DeviceID(rawID), IdentitySPKI: p.IdentitySPKI, RequestSPKI: p.RequestSPKI, MSISDN: msisdn,
		SIMFingerprint: p.SIMFingerprint, StrongBox: idRes.SecurityLevel == attest.LevelStrongBox,
		ReceiverID: receiverID, Active: true, BoundAt: now,
	}
	if err := s.store.SaveDevice(ctx, dev); err != nil {
		return nil, err
	}
	s.log.InfoContext(ctx, "device bound", "device_id", dev.ID, "strongbox", dev.StrongBox, "msisdn", domain.MaskMSISDN(msisdn))
	return &CompleteResult{DeviceID: dev.ID, ReceiverCertificate: cert, ReceiverID: receiverID}, nil
}

func (s *Service) awaitSMS(ctx context.Context, id [16]byte) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, s.cfg.SMSWait)
	defer cancel()
	tick := time.NewTicker(400 * time.Millisecond)
	defer tick.Stop()
	for {
		sess, ok, err := s.store.SessionByID(ctx, id)
		if err != nil {
			return "", err
		}
		if ok && sess.MSISDN != "" {
			return sess.MSISDN, nil
		}
		select {
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				return "", domain.ErrSMSPending
			}
			return "", ctx.Err()
		case <-tick.C:
		}
	}
}

// Device returns a bound device for the gateway's registry lookups.
func (s *Service) Device(ctx context.Context, id string) (domain.Device, bool, error) {
	return s.store.Device(ctx, id)
}
