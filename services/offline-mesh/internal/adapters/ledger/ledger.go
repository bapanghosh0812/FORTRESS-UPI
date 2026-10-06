// Package ledger implements app.Ledger: an in-memory recorder for development
// and an HTTP adapter that posts balanced journals to core-ledger.
package ledger

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
)

// Memory records debits (dev/test), idempotent on key.
type Memory struct {
	mu     sync.Mutex
	Debits map[string]uint64
}

// NewMemory returns an empty recorder.
func NewMemory() *Memory { return &Memory{Debits: map[string]uint64{}} }

// DebitToOfflinePool implements app.Ledger.
func (m *Memory) DebitToOfflinePool(_ context.Context, _, _ string, _ domain.ID16, amount uint64, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.Debits[key]; !ok {
		m.Debits[key] = amount
	}
	return nil
}

// HTTP posts a journal to core-ledger over the mesh:
// debit the user's account, credit the device's offline-pool sub-ledger.
type HTTP struct {
	base   string
	client *http.Client
}

// NewHTTP targets core-ledger at base (e.g. http://core-ledger:8080).
func NewHTTP(base string) *HTTP {
	return &HTTP{base: strings.TrimRight(base, "/"), client: &http.Client{Timeout: 3 * time.Second}}
}

// DebitToOfflinePool implements app.Ledger.
func (h *HTTP) DebitToOfflinePool(ctx context.Context, _, accountRef string, wallet domain.ID16, amount uint64, key string) error {
	body, err := json.Marshal(map[string]any{
		"id":        key,
		"narrative": "offline vault load",
		"entries": []map[string]any{
			{"account": "user:" + strings.ToLower(accountRef), "direction": "DEBIT", "amountPaise": amount},
			{"account": "pool:offline:" + hex.EncodeToString(wallet[:]), "direction": "CREDIT", "amountPaise": amount},
		},
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.base+"/v1/journals", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := h.client.Do(req)
	if err != nil {
		return fmt.Errorf("core-ledger: %w", err)
	}
	defer resp.Body.Close()
	// 201 = posted, 200 = idempotent duplicate; anything else fails issuance.
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("core-ledger: status %d", resp.StatusCode)
	}
	return nil
}
