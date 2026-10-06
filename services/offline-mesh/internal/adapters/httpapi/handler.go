// Package httpapi is the inbound HTTP adapter for offline settlement.
//
// The service is reachable only through security-gateway (device signature
// already verified) over mesh mTLS; NetworkPolicy blocks every other caller.
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/app"
	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
	"github.com/fortress-upi/fortress/shared/crypto/fce"
)

// ContentType is the media type for FCE-encoded bodies.
const ContentType = "application/vnd.fortress.fce"

// Submitter is the use-case port this adapter drives.
type Submitter interface {
	Submit(ctx context.Context, raw []byte) (domain.Outcome, error)
}

type handler struct {
	svc Submitter
	log *slog.Logger
}

// Routes registers the API on mux.
func Routes(mux *http.ServeMux, svc Submitter, log *slog.Logger) {
	h := &handler{svc: svc, log: log}
	mux.HandleFunc("POST /v1/offline/settlements", h.settle)
}

type response struct {
	Outcome string `json:"outcome,omitempty"`
	Error   string `json:"error,omitempty"`
}

func (h *handler) settle(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Content-Type") != ContentType {
		writeJSON(w, http.StatusUnsupportedMediaType, response{Error: "unsupported_media_type"})
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, fce.MaxMessageSize))
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeJSON(w, http.StatusRequestEntityTooLarge, response{Error: "too_large"})
			return
		}
		writeJSON(w, http.StatusBadRequest, response{Error: "bad_request"})
		return
	}

	outcome, err := h.svc.Submit(r.Context(), raw)
	switch {
	case errors.Is(err, app.ErrInvalid):
		writeJSON(w, http.StatusUnprocessableEntity, response{Error: reasonCode(err)})
	case err != nil:
		h.log.ErrorContext(r.Context(), "settlement failed", "err", err)
		writeJSON(w, http.StatusServiceUnavailable, response{Error: "unavailable"})
	case outcome == domain.OutcomeConflict:
		writeJSON(w, http.StatusConflict, response{Outcome: outcome.String()})
	default:
		writeJSON(w, http.StatusOK, response{Outcome: outcome.String()})
	}
}

// reasonCode maps validation errors to a stable, non-sensitive code.
func reasonCode(err error) string {
	for _, e := range []error{
		domain.ErrExpired, domain.ErrAmount, domain.ErrDuplicateNote, domain.ErrMixedWallets,
		domain.ErrPolicy, domain.ErrUnknownIssuer, domain.ErrIssuerSignature, domain.ErrSpendSignature,
	} {
		if errors.Is(err, e) {
			return e.Error()
		}
	}
	return domain.ErrMalformed.Error()
}

func writeJSON(w http.ResponseWriter, status int, v response) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
