// Package httpapi exposes binding to devices (via security-gateway, unsigned
// public prefix /v1/bind/) and internal endpoints to the operator SMS gateway
// and to security-gateway's device registry.
package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"github.com/fortress-upi/fortress/services/identity/internal/app"
	"github.com/fortress-upi/fortress/services/identity/internal/domain"
)

const maxBody = 64 << 10

// Binder is the use-case port.
type Binder interface {
	Begin(ctx context.Context) (app.BeginResult, error)
	ReceiveSMS(ctx context.Context, from, text string) error
	Complete(ctx context.Context, req app.CompleteRequest) (*app.CompleteResult, error)
	Device(ctx context.Context, id string) (domain.Device, bool, error)
}

type handler struct {
	svc       Binder
	smsSecret []byte
	log       *slog.Logger
}

// Routes registers all endpoints.
func Routes(mux *http.ServeMux, svc Binder, smsSecret []byte, log *slog.Logger) {
	h := &handler{svc: svc, smsSecret: smsSecret, log: log}
	mux.HandleFunc("POST /v1/bind/begin", h.begin)
	mux.HandleFunc("POST /v1/bind/complete", h.complete)
	mux.HandleFunc("POST /internal/sms", h.sms)
	mux.HandleFunc("GET /internal/devices/{id}", h.device)
}

var b64 = base64.StdEncoding

func (h *handler) begin(w http.ResponseWriter, r *http.Request) {
	res, err := h.svc.Begin(r.Context())
	if err != nil {
		h.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"sessionId":     b64.EncodeToString(res.Session.ID[:]),
		"challenge":     b64.EncodeToString(res.Session.Challenge[:]),
		"smsToken":      res.Session.SMSToken,
		"virtualNumber": res.VirtualNumber,
	})
}

type completeDTO struct {
	Payload           string   `json:"payload"`
	IdentitySignature string   `json:"identitySignature"`
	IdentityChain     []string `json:"identityChain"`
	RequestChain      []string `json:"requestChain"`
	IntegrityToken    string   `json:"integrityToken"`
	RiskReport        string   `json:"riskReport"`
}

func (h *handler) complete(w http.ResponseWriter, r *http.Request) {
	var in completeDTO
	if !decode(w, r, &in) {
		return
	}
	req := app.CompleteRequest{IntegrityToken: in.IntegrityToken}
	var err error
	if req.Payload, err = b64.DecodeString(in.Payload); err == nil {
		req.IdentitySignature, err = b64.DecodeString(in.IdentitySignature)
	}
	if err == nil {
		req.RiskReport, err = b64.DecodeString(in.RiskReport)
	}
	if err == nil {
		req.IdentityChain, err = decodeChain(in.IdentityChain)
	}
	if err == nil {
		req.RequestChain, err = decodeChain(in.RequestChain)
	}
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	res, err := h.svc.Complete(r.Context(), req)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"deviceId":            res.DeviceID,
		"receiverCertificate": b64.EncodeToString(res.ReceiverCertificate),
		"receiverId":          b64.EncodeToString(res.ReceiverID[:]),
	})
}

// sms receives operator-gateway callbacks, authenticated by HMAC-SHA256 over the raw body.
func (h *handler) sms(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4<<10))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	got, err := hex.DecodeString(r.Header.Get("X-Operator-Signature"))
	mac := hmac.New(sha256.New, h.smsSecret)
	mac.Write(body)
	if err != nil || len(h.smsSecret) == 0 || !hmac.Equal(got, mac.Sum(nil)) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	var in struct {
		From string `json:"from"`
		Text string `json:"text"`
	}
	if err := json.Unmarshal(body, &in); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	if err := h.svc.ReceiveSMS(r.Context(), in.From, in.Text); err != nil {
		h.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *handler) device(w http.ResponseWriter, r *http.Request) {
	d, ok, err := h.svc.Device(r.Context(), r.PathValue("id"))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	if !ok {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": d.ID, "requestSpki": b64.EncodeToString(d.RequestSPKI), "active": d.Active})
}

func (h *handler) fail(w http.ResponseWriter, r *http.Request, err error) {
	status := http.StatusInternalServerError
	switch {
	case errors.Is(err, domain.ErrInvalid):
		status = http.StatusUnprocessableEntity
	case errors.Is(err, domain.ErrSession), errors.Is(err, domain.ErrExpired), errors.Is(err, domain.ErrReplay):
		status = http.StatusGone
	case errors.Is(err, domain.ErrSMSPending):
		status = http.StatusRequestTimeout
	case errors.Is(err, domain.ErrAttestation), errors.Is(err, domain.ErrIntegrity):
		status = http.StatusForbidden
	default:
		h.log.ErrorContext(r.Context(), "binding error", "err", err)
		writeJSON(w, status, map[string]string{"error": "unavailable"})
		return
	}
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return false
	}
	return true
}

func decodeChain(in []string) ([][]byte, error) {
	if len(in) == 0 || len(in) > 10 {
		return nil, errors.New("chain length")
	}
	out := make([][]byte, len(in))
	for i, s := range in {
		der, err := b64.DecodeString(s)
		if err != nil {
			return nil, err
		}
		out[i] = der
	}
	return out, nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
