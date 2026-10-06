package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/app"
)

// Issuer is the issuance use-case port.
type Issuer interface {
	Challenge(ctx context.Context, deviceID string) ([32]byte, error)
	Issue(ctx context.Context, deviceID string, challenge [32]byte, accountRef string, notes []app.NoteRequest) ([]app.IssuedNote, error)
}

type issuanceHandler struct {
	svc Issuer
	log *slog.Logger
}

// IssuanceRoutes registers vault-loading endpoints. The device identity comes
// from security-gateway's X-Fortress-Verified-Device assertion.
func IssuanceRoutes(mux *http.ServeMux, svc Issuer, log *slog.Logger) {
	h := &issuanceHandler{svc: svc, log: log}
	mux.HandleFunc("POST /v1/offline/issuance/challenge", h.challenge)
	mux.HandleFunc("POST /v1/offline/issuance", h.issue)
}

var b64 = base64.StdEncoding

func (h *issuanceHandler) challenge(w http.ResponseWriter, r *http.Request) {
	device := r.Header.Get("X-Fortress-Verified-Device")
	if device == "" {
		writeAny(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	c, err := h.svc.Challenge(r.Context(), device)
	if err != nil {
		h.log.ErrorContext(r.Context(), "challenge failed", "err", err)
		writeAny(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	writeAny(w, http.StatusOK, map[string]string{"challenge": b64.EncodeToString(c[:])})
}

type issueDTO struct {
	Challenge  string `json:"challenge"`
	AccountRef string `json:"accountRef"`
	Notes      []struct {
		KeyAlias          string   `json:"keyAlias"`
		DenominationPaise uint64   `json:"denominationPaise"`
		PublicKey         string   `json:"publicKey"`
		AttestationChain  []string `json:"attestationChain"`
	} `json:"notes"`
}

func (h *issuanceHandler) issue(w http.ResponseWriter, r *http.Request) {
	device := r.Header.Get("X-Fortress-Verified-Device")
	if device == "" {
		writeAny(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 512<<10))
	dec.DisallowUnknownFields()
	var in issueDTO
	if err := dec.Decode(&in); err != nil {
		writeAny(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	raw, err := b64.DecodeString(in.Challenge)
	if err != nil || len(raw) != 32 {
		writeAny(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	var challenge [32]byte
	copy(challenge[:], raw)
	notes := make([]app.NoteRequest, 0, len(in.Notes))
	for _, n := range in.Notes {
		pk, err := b64.DecodeString(n.PublicKey)
		if err != nil || len(n.AttestationChain) == 0 || len(n.AttestationChain) > 10 {
			writeAny(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
			return
		}
		chain := make([][]byte, len(n.AttestationChain))
		for i, c := range n.AttestationChain {
			if chain[i], err = b64.DecodeString(c); err != nil {
				writeAny(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
				return
			}
		}
		notes = append(notes, app.NoteRequest{KeyAlias: n.KeyAlias, DenominationPaise: n.DenominationPaise, PublicKey: pk, AttestationChain: chain})
	}

	issued, err := h.svc.Issue(r.Context(), device, challenge, in.AccountRef, notes)
	switch {
	case errors.Is(err, app.ErrIssueInvalid):
		writeAny(w, http.StatusUnprocessableEntity, map[string]string{"error": "invalid_issuance"})
		return
	case errors.Is(err, app.ErrChallenge):
		writeAny(w, http.StatusGone, map[string]string{"error": "challenge_invalid"})
		return
	case errors.Is(err, app.ErrAttestation):
		writeAny(w, http.StatusForbidden, map[string]string{"error": "attestation_failed"})
		return
	case err != nil:
		h.log.ErrorContext(r.Context(), "issuance failed", "err", err)
		writeAny(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
		return
	}
	out := make([]map[string]string, 0, len(issued))
	for _, n := range issued {
		out = append(out, map[string]string{"keyAlias": n.KeyAlias, "certificate": b64.EncodeToString(n.Certificate)})
	}
	writeAny(w, http.StatusOK, map[string]any{"certificates": out})
}

func writeAny(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
