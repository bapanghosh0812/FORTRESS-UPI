package devicesig_test

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/security-gateway/internal/adapters/memnonce"
	"github.com/fortress-upi/fortress/services/security-gateway/internal/devicesig"
	"github.com/fortress-upi/fortress/shared/crypto/domains"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// Shared with RequestSignerTest.kt — the Android signer must produce these bytes.
const goldenCanonicalHex = "0100000004504f535402000000172f76312f6f66666c696e652f736574746c656d656e747303000000000400000020" +
	"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85505000000080000018bcfe5680006000000100102030405060708090a0b0c0d0e0f10" +
	"07000000146465765f30313233343536373839616263646566"

const deviceID = "dev_0123456789abcdef"

func TestCanonicalGolden(t *testing.T) {
	nonce := []byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}
	got, err := devicesig.CanonicalRequest("POST", "/v1/offline/settlements", "", sha256.Sum256(nil), 1_700_000_000_000, nonce, deviceID)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(got) != goldenCanonicalHex {
		t.Fatalf("canonical mismatch:\n%x", got)
	}
}

type staticRegistry map[string]devicesig.Device

func (s staticRegistry) Lookup(_ context.Context, id string) (devicesig.Device, bool, error) {
	d, ok := s[id]
	return d, ok, nil
}

type harness struct {
	key     *ecdsa.PrivateKey
	handler http.Handler
	now     time.Time
	reached *string
}

type alwaysFresh struct{}

func (alwaysFresh) Remember(context.Context, string, time.Duration) (bool, error) { return true, nil }

func newHarness(t testing.TB, nonces devicesig.NonceStore) harness {
	t.Helper()
	k, err := sig.GenerateP256()
	if err != nil {
		t.Fatal(err)
	}
	now := time.UnixMilli(1_700_000_000_000)
	if nonces == nil {
		nonces = memnonce.New(1_000, func() time.Time { return now })
	}
	reached := new(string)
	v := devicesig.NewVerifier(
		staticRegistry{deviceID: {ID: deviceID, PublicKey: &k.PublicKey, Active: true}},
		nonces,
		func() time.Time { return now },
		slog.New(slog.NewTextHandler(io.Discard, nil)))
	h := v.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*reached = r.Header.Get(devicesig.HeaderVerified)
		body, _ := io.ReadAll(r.Body)
		_, _ = w.Write(body)
	}))
	return harness{key: k, handler: h, now: now, reached: reached}
}

func (h harness) signed(t testing.TB, body []byte, nonce byte, ts time.Time) *http.Request {
	t.Helper()
	n := bytes.Repeat([]byte{nonce}, 16)
	c, err := devicesig.CanonicalRequest(http.MethodPost, "/v1/offline/settlements", "", sha256.Sum256(body),
		uint64(ts.UnixMilli()), n, deviceID)
	if err != nil {
		t.Fatal(err)
	}
	s, err := sig.Sign(h.key, domains.Request, c)
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest(http.MethodPost, "/v1/offline/settlements", bytes.NewReader(body))
	r.Header.Set(devicesig.HeaderDevice, deviceID)
	r.Header.Set(devicesig.HeaderTimestamp, strconv.FormatInt(ts.UnixMilli(), 10))
	r.Header.Set(devicesig.HeaderNonce, base64.RawURLEncoding.EncodeToString(n))
	r.Header.Set(devicesig.HeaderSignature, base64.RawURLEncoding.EncodeToString(s))
	return r
}

func serve(h http.Handler, r *http.Request) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestValidRequestPassesWithBodyIntact(t *testing.T) {
	h := newHarness(t, nil)
	w := serve(h.handler, h.signed(t, []byte("payload"), 1, h.now))
	if w.Code != http.StatusOK || w.Body.String() != "payload" || *h.reached != deviceID {
		t.Fatalf("code=%d body=%q device=%q", w.Code, w.Body.String(), *h.reached)
	}
}

func TestRejections(t *testing.T) {
	cases := map[string]func(h harness) *http.Request{
		"replayed nonce": func(h harness) *http.Request {
			serve(h.handler, h.signed(t, []byte("x"), 2, h.now))
			return h.signed(t, []byte("x"), 2, h.now)
		},
		"tampered body": func(h harness) *http.Request {
			r := h.signed(t, []byte("amount=1"), 3, h.now)
			r.Body = io.NopCloser(bytes.NewReader([]byte("amount=9")))
			return r
		},
		"stale timestamp": func(h harness) *http.Request {
			return h.signed(t, []byte("x"), 4, h.now.Add(-31*time.Second))
		},
		"spoofed verified header": func(h harness) *http.Request {
			r := h.signed(t, []byte("x"), 5, h.now)
			r.Header.Set(devicesig.HeaderDevice, "dev_attacker_controlled_id")
			r.Header.Set(devicesig.HeaderVerified, deviceID)
			return r
		},
	}
	for name, build := range cases {
		t.Run(name, func(t *testing.T) {
			h := newHarness(t, nil)
			if w := serve(h.handler, build(h)); w.Code != http.StatusUnauthorized {
				t.Fatalf("code = %d, want 401", w.Code)
			}
		})
	}
}

// BenchmarkVerify measures the success path (header parse, body hash, ECDSA
// verify); the nonce store is stubbed so one signed request can be replayed.
func BenchmarkVerify(b *testing.B) {
	h := newHarness(b, alwaysFresh{})
	tmpl := h.signed(b, []byte("payload"), 1, h.now)
	b.ReportAllocs()
	for b.Loop() {
		r := tmpl.Clone(context.Background())
		r.Body = io.NopCloser(bytes.NewReader([]byte("payload")))
		if w := serve(h.handler, r); w.Code != http.StatusOK {
			b.Fatalf("code = %d", w.Code)
		}
	}
}
