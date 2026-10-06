// Package httpapi is the inbound HTTP adapter for the ledger. It is an
// internal API: callers are platform services authenticated by mesh mTLS.
package httpapi

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/fortress-upi/fortress/services/core-ledger/internal/app"
	"github.com/fortress-upi/fortress/services/core-ledger/internal/domain"
)

const maxBody = 64 << 10

type entryDTO struct {
	Account     string `json:"account"`
	Direction   string `json:"direction"`
	AmountPaise uint64 `json:"amountPaise"`
}

type journalDTO struct {
	ID        string     `json:"id"`
	Narrative string     `json:"narrative"`
	Entries   []entryDTO `json:"entries"`
}

type handler struct {
	svc *app.PostService
	log *slog.Logger
}

// Routes registers the ledger API.
func Routes(mux *http.ServeMux, svc *app.PostService, log *slog.Logger) {
	h := &handler{svc: svc, log: log}
	mux.HandleFunc("POST /v1/journals", h.post)
}

func (h *handler) post(w http.ResponseWriter, r *http.Request) {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	dec.DisallowUnknownFields()
	var in journalDTO
	if err := dec.Decode(&in); err != nil || dec.More() {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad_request"})
		return
	}
	j := domain.Journal{ID: in.ID, Narrative: in.Narrative, Entries: make([]domain.Entry, 0, len(in.Entries))}
	for _, e := range in.Entries {
		var d domain.Direction
		switch e.Direction {
		case "DEBIT":
			d = domain.Debit
		case "CREDIT":
			d = domain.Credit
		}
		j.Entries = append(j.Entries, domain.Entry{Account: e.Account, Direction: d, AmountPaise: e.AmountPaise})
	}

	created, err := h.svc.Post(r.Context(), j)
	switch {
	case errors.Is(err, domain.ErrInvalid), errors.Is(err, domain.ErrUnbalanced):
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": err.Error()})
	case errors.Is(err, domain.ErrIdempotencyConflict):
		writeJSON(w, http.StatusConflict, map[string]string{"error": "idempotency_conflict"})
	case err != nil:
		h.log.ErrorContext(r.Context(), "post failed", "err", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "unavailable"})
	case created:
		writeJSON(w, http.StatusCreated, map[string]string{"status": "POSTED"})
	default:
		writeJSON(w, http.StatusOK, map[string]string{"status": "DUPLICATE"})
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
