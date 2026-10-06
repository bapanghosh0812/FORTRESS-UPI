// Package httpapi exposes payments to devices. Requests arrive only through
// security-gateway, which verifies the device signature and asserts the
// device in X-Fortress-Verified-Device (stripping any client-sent value).
package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"github.com/fortress-upi/fortress/services/payments/internal/app"
	"github.com/fortress-upi/fortress/services/payments/internal/domain"
)

const verifiedDevice = "X-Fortress-Verified-Device"

// Service is the use-case port.
type Service interface {
	Create(ctx context.Context, p domain.Payment) (domain.Payment, error)
	Authorize(ctx context.Context, deviceID, id, credential string) (domain.Payment, error)
	Status(ctx context.Context, deviceID, id string) (domain.Payment, error)
	Callback(ctx context.Context, pspRef string, res app.PSPResult) error
	Transactions(ctx context.Context, deviceID string) ([]domain.Payment, error)
	Accounts(ctx context.Context, deviceID string) ([]app.Account, error)
}

type handler struct {
	svc       Service
	pspSecret []byte
	log       *slog.Logger
}

// Routes registers the API.
func Routes(mux *http.ServeMux, svc Service, pspSecret []byte, log *slog.Logger) {
	h := &handler{svc: svc, pspSecret: pspSecret, log: log}
	mux.HandleFunc("GET /v1/accounts", h.device(h.accounts))
	mux.HandleFunc("GET /v1/transactions", h.device(h.transactions))
	mux.HandleFunc("POST /v1/payments", h.device(h.create))
	mux.HandleFunc("POST /v1/payments/{id}/authorize", h.device(h.authorize))
	mux.HandleFunc("GET /v1/payments/{id}", h.device(h.status))
	mux.HandleFunc("POST /internal/psp/callback", h.callback)
}

type deviceHandler func(w http.ResponseWriter, r *http.Request, deviceID string)

func (h *handler) device(next deviceHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get(verifiedDevice)
		if id == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r, id)
	}
}

func (h *handler) accounts(w http.ResponseWriter, r *http.Request, device string) {
	accts, err := h.svc.Accounts(r.Context(), device)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	out := make([]map[string]any, 0, len(accts))
	for _, a := range accts {
		out = append(out, map[string]any{"ref": a.Ref, "bank": a.Bank, "masked": a.Masked, "primary": a.Primary})
	}
	writeJSON(w, http.StatusOK, map[string]any{"accounts": out})
}

func (h *handler) transactions(w http.ResponseWriter, r *http.Request, device string) {
	list, err := h.svc.Transactions(r.Context(), device)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	out := make([]map[string]any, 0, len(list))
	for _, p := range list {
		name := p.PayeeName
		if name == "" {
			name = p.PayeeVPA
		}
		out = append(out, map[string]any{
			"id": p.ID, "counterparty": name, "note": p.Note, "amountPaise": p.AmountPaise, "credit": false,
			"at": p.CreatedAt.UnixMilli(), "status": string(p.State), "upiRef": p.UPIRef,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"transactions": out})
}

type createDTO struct {
	PayeeVPA     string  `json:"payeeVpa"`
	PayeeName    *string `json:"payeeName"`
	AmountPaise  uint64  `json:"amountPaise"`
	Note         *string `json:"note"`
	MerchantCode *string `json:"merchantCode"`
	TxnRef       *string `json:"txnRef"`
	AccountRef   string  `json:"accountRef"`
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func (h *handler) create(w http.ResponseWriter, r *http.Request, device string) {
	var in createDTO
	if !decode(w, r, &in) {
		return
	}
	p, err := h.svc.Create(r.Context(), domain.Payment{
		DeviceID: device, IdempotencyKey: r.Header.Get("Idempotency-Key"), AccountRef: in.AccountRef,
		PayeeVPA: in.PayeeVPA, PayeeName: deref(in.PayeeName), AmountPaise: in.AmountPaise,
		Note: deref(in.Note), MerchantCode: deref(in.MerchantCode), TxnRef: deref(in.TxnRef),
	})
	if err != nil {
		h.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"id": p.ID, "clChallenge": p.CLChallenge, "state": string(p.State)})
}

func (h *handler) authorize(w http.ResponseWriter, r *http.Request, device string) {
	var in struct {
		Credential string `json:"credential"`
	}
	if !decode(w, r, &in) {
		return
	}
	p, err := h.svc.Authorize(r.Context(), device, r.PathValue("id"), in.Credential)
	if err != nil {
		h.fail(w, r, err)
		return
	}
	writeStatus(w, p)
}

func (h *handler) status(w http.ResponseWriter, r *http.Request, device string) {
	p, err := h.svc.Status(r.Context(), device, r.PathValue("id"))
	if err != nil {
		h.fail(w, r, err)
		return
	}
	writeStatus(w, p)
}

// callback receives PSP notifications authenticated with HMAC-SHA256 over the body.
func (h *handler) callback(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 16<<10))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	got, err := hex.DecodeString(r.Header.Get("X-PSP-Signature"))
	mac := hmac.New(sha256.New, h.pspSecret)
	mac.Write(body)
	if err != nil || len(h.pspSecret) == 0 || !hmac.Equal(got, mac.Sum(nil)) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	var in struct {
		PSPRef string `json:"pspRef"`
		State  string `json:"state"`
		UPIRef string `json:"upiRef"`
		Reason string `json:"reason"`
	}
	if err := json.Unmarshal(body, &in); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	st := domain.State(in.State)
	if st != domain.Success && st != domain.Failed && st != domain.Deemed {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_state"})
		return
	}
	if err := h.svc.Callback(r.Context(), in.PSPRef, app.PSPResult{State: st, UPIRef: in.UPIRef, Reason: in.Reason}); err != nil {
		h.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeStatus(w http.ResponseWriter, p domain.Payment) {
	writeJSON(w, http.StatusOK, map[string]string{"id": p.ID, "state": string(p.State), "upiRef": p.UPIRef, "reason": p.Reason})
}

func (h *handler) fail(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalid):
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "invalid_payment"})
	case errors.Is(err, domain.ErrNotFound):
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not_found"})
	case errors.Is(err, domain.ErrIdempotency):
		writeJSON(w, http.StatusConflict, map[string]string{"error": "idempotency_conflict"})
	default:
		h.log.ErrorContext(r.Context(), "payments error", "err", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
	}
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
