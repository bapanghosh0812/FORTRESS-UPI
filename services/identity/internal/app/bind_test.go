package app_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/identity/internal/adapters/memstore"
	"github.com/fortress-upi/fortress/services/identity/internal/app"
	"github.com/fortress-upi/fortress/services/identity/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/attest"
	"github.com/fortress-upi/fortress/shared/crypto/attest/attesttest"
	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
	"github.com/fortress-upi/fortress/shared/crypto/signer"
)

type okIntegrity struct{}

func (okIntegrity) Verify(context.Context, string, [32]byte) error { return nil }

var digest = sha256.Sum256([]byte("fortress-release-cert"))

type rig struct {
	svc      *app.Service
	store    *memstore.Store
	ca       *attesttest.CA
	issuer   *signer.Software
	now      time.Time
	identity *ecdsa.PrivateKey
	request  *ecdsa.PrivateKey
}

func newRig(t *testing.T) *rig {
	t.Helper()
	ca, err := attesttest.NewCA()
	if err != nil {
		t.Fatal(err)
	}
	issuer, err := signer.Generate([]byte{0xAA, 0x01})
	if err != nil {
		t.Fatal(err)
	}
	r := &rig{store: memstore.New(), ca: ca, issuer: issuer, now: time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)}
	r.identity, _ = sig.GenerateP256()
	r.request, _ = sig.GenerateP256()
	r.svc = app.New(r.store, okIntegrity{}, issuer, func() time.Time { return r.now }, app.Config{
		VirtualNumber: "+919000000000", SessionTTL: 10 * time.Minute, SMSWait: 200 * time.Millisecond,
		CertTTL: 365 * 24 * time.Hour, MaxClockSkew: 5 * time.Minute,
		Attestation: attest.Policy{
			Roots: [][]byte{ca.RootSPKI()}, PackageName: "com.fortress.upi", SignerDigests: [][]byte{digest[:]},
			MinLevel: attest.LevelTEE, RequireLockedVerifiedBoot: true,
		},
	}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return r
}

func (r *rig) completeRequest(t *testing.T, sess domain.Session, pkg string) app.CompleteRequest {
	t.Helper()
	idSPKI, _ := sig.MarshalPublicKey(&r.identity.PublicKey)
	rqSPKI, _ := sig.MarshalPublicKey(&r.request.PublicKey)
	sim := sha256.Sum256([]byte("sim"))
	payload, err := fce.NewEncoder().Bytes(1, sess.ID[:]).Bytes(2, idSPKI).Bytes(3, rqSPKI).Bytes(4, sim[:]).
		Uint64(5, uint64(r.now.UnixMilli())).Finish()
	if err != nil {
		t.Fatal(err)
	}
	signature, _ := sig.Sign(r.identity, domains.Binding, payload)
	opts := attesttest.Options{Level: 2, Package: pkg, SignerDigest: digest[:]}
	idChain, err := r.ca.Attest(&r.identity.PublicKey, sess.Challenge[:], opts)
	if err != nil {
		t.Fatal(err)
	}
	rqChain, err := r.ca.Attest(&r.request.PublicKey, sess.Challenge[:], opts)
	if err != nil {
		t.Fatal(err)
	}
	return app.CompleteRequest{Payload: payload, IdentitySignature: signature, IdentityChain: idChain, RequestChain: rqChain, IntegrityToken: "t"}
}

func TestBindingHappyPathIssuesVerifiableReceiverCert(t *testing.T) {
	r := newRig(t)
	ctx := context.Background()
	begin, err := r.svc.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := r.svc.ReceiveSMS(ctx, "919876543210", "FORTRESS "+begin.Session.SMSToken); err != nil {
		t.Fatalf("sms: %v", err)
	}
	res, err := r.svc.Complete(ctx, r.completeRequest(t, begin.Session, "com.fortress.upi"))
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	env, err := fce.Decode(res.ReceiverCertificate, 1, 2)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := env.Bytes(1)
	signature, _ := env.Bytes(2)
	if err := sig.Verify(r.issuer.Public(), domains.ReceiverCert, body, signature); err != nil {
		t.Fatalf("receiver cert not issuer-signed: %v", err)
	}
	dev, ok, _ := r.svc.Device(ctx, res.DeviceID)
	if !ok || !dev.Active || !dev.StrongBox || dev.MSISDN != "+919876543210" {
		t.Fatalf("device = %+v", dev)
	}

	// The same session can never bind a second device.
	if _, err := r.svc.Complete(ctx, r.completeRequest(t, begin.Session, "com.fortress.upi")); !errors.Is(err, domain.ErrReplay) {
		t.Fatalf("replay: %v", err)
	}
}

func TestBindingRejections(t *testing.T) {
	ctx := context.Background()

	t.Run("no SMS from the SIM", func(t *testing.T) {
		r := newRig(t)
		begin, _ := r.svc.Begin(ctx)
		if _, err := r.svc.Complete(ctx, r.completeRequest(t, begin.Session, "com.fortress.upi")); !errors.Is(err, domain.ErrSMSPending) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("repackaged app", func(t *testing.T) {
		r := newRig(t)
		begin, _ := r.svc.Begin(ctx)
		_ = r.svc.ReceiveSMS(ctx, "+919876543210", "FORTRESS "+begin.Session.SMSToken)
		if _, err := r.svc.Complete(ctx, r.completeRequest(t, begin.Session, "com.clone.app")); !errors.Is(err, domain.ErrAttestation) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("expired session", func(t *testing.T) {
		r := newRig(t)
		begin, _ := r.svc.Begin(ctx)
		_ = r.svc.ReceiveSMS(ctx, "+919876543210", "FORTRESS "+begin.Session.SMSToken)
		req := r.completeRequest(t, begin.Session, "com.fortress.upi")
		r.now = r.now.Add(11 * time.Minute)
		if _, err := r.svc.Complete(ctx, req); !errors.Is(err, domain.ErrExpired) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("malformed SMS", func(t *testing.T) {
		r := newRig(t)
		if err := r.svc.ReceiveSMS(ctx, "+919876543210", "hello"); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("got %v", err)
		}
	})
}
