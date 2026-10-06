package httpapi

import (
	"context"
	"errors"
	"log/slog"
	"net/http"

	"github.com/fortress-upi/fortress/services/payments/internal/app"
)

// DirectoryService is the lookup and policy port.
type DirectoryService interface {
	Limits() app.Limits
	ValidateAddress(ctx context.Context, vpa string) (app.Payee, error)
	ResolveMobile(ctx context.Context, mobile string) (app.Payee, error)
	Profile(ctx context.Context, deviceID string) (app.Profile, error)
}

// DirectoryRoutes registers limits, profile and payee lookups. Every route
// requires a gateway-verified device; lookups are rate limited at the gateway
// because a directory is an enumeration target.
func DirectoryRoutes(mux *http.ServeMux, svc DirectoryService, log *slog.Logger) {
	h := &handler{log: log}
	mux.HandleFunc("GET /v1/limits", h.device(func(w http.ResponseWriter, _ *http.Request, _ string) {
		w.Header().Set("Cache-Control", "private, max-age=300")
		writeJSON(w, http.StatusOK, svc.Limits())
	}))
	mux.HandleFunc("GET /v1/profile", h.device(func(w http.ResponseWriter, r *http.Request, device string) {
		p, err := svc.Profile(r.Context(), device)
		if err != nil {
			h.lookupFail(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, p)
	}))
	mux.HandleFunc("GET /v1/directory/vpa/{vpa}", h.device(func(w http.ResponseWriter, r *http.Request, _ string) {
		p, err := svc.ValidateAddress(r.Context(), r.PathValue("vpa"))
		if err != nil {
			h.lookupFail(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, p)
	}))
	mux.HandleFunc("GET /v1/directory/mobile/{mobile}", h.device(func(w http.ResponseWriter, r *http.Request, _ string) {
		p, err := svc.ResolveMobile(r.Context(), r.PathValue("mobile"))
		if err != nil {
			h.lookupFail(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, p)
	}))
}

func (h *handler) lookupFail(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, app.ErrInvalidAddress):
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_address"})
	case errors.Is(err, app.ErrNoUPIAccount):
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "vpa_not_found"})
	default:
		h.log.ErrorContext(r.Context(), "directory error", "err", err)
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": "psp_unavailable"})
	}
}
