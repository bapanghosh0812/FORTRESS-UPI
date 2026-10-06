// Package httpregistry resolves bound devices from the identity service with a
// short cache, so revocations (SIM change, fraud hold) propagate in seconds.
package httpregistry

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/fortress-upi/fortress/services/security-gateway/internal/devicesig"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

type cached struct {
	dev     devicesig.Device
	found   bool
	expires time.Time
}

// Registry implements devicesig.Registry.
type Registry struct {
	base   string
	client *http.Client
	ttl    time.Duration
	max    int

	mu    sync.Mutex
	cache map[string]cached
}

// New targets the identity service at base (e.g. http://identity:8080).
func New(base string, ttl time.Duration) *Registry {
	return &Registry{
		base: strings.TrimRight(base, "/"), client: &http.Client{Timeout: 800 * time.Millisecond},
		ttl: ttl, max: 200_000, cache: map[string]cached{},
	}
}

// Lookup implements devicesig.Registry.
func (r *Registry) Lookup(ctx context.Context, id string) (devicesig.Device, bool, error) {
	now := time.Now()
	r.mu.Lock()
	c, ok := r.cache[id]
	r.mu.Unlock()
	if ok && now.Before(c.expires) {
		return c.dev, c.found, nil
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.base+"/internal/devices/"+url.PathEscape(id), nil)
	if err != nil {
		return devicesig.Device{}, false, err
	}
	resp, err := r.client.Do(req)
	if err != nil {
		return devicesig.Device{}, false, fmt.Errorf("identity: %w", err)
	}
	defer resp.Body.Close()

	var entry cached
	switch resp.StatusCode {
	case http.StatusNotFound:
		entry = cached{found: false}
	case http.StatusOK:
		var in struct {
			ID          string `json:"id"`
			RequestSPKI string `json:"requestSpki"`
			Active      bool   `json:"active"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(nil, resp.Body, 8<<10)).Decode(&in); err != nil {
			return devicesig.Device{}, false, fmt.Errorf("identity: decode: %w", err)
		}
		der, err := base64.StdEncoding.DecodeString(in.RequestSPKI)
		if err != nil {
			return devicesig.Device{}, false, fmt.Errorf("identity: key: %w", err)
		}
		pub, err := sig.ParseP256PublicKey(der)
		if err != nil {
			return devicesig.Device{}, false, fmt.Errorf("identity: key: %w", err)
		}
		entry = cached{dev: devicesig.Device{ID: in.ID, PublicKey: pub, Active: in.Active}, found: true}
	default:
		return devicesig.Device{}, false, fmt.Errorf("identity: status %d", resp.StatusCode)
	}
	entry.expires = now.Add(r.ttl)

	r.mu.Lock()
	if len(r.cache) >= r.max {
		r.cache = map[string]cached{} // coarse reset keeps memory bounded
	}
	r.cache[id] = entry
	r.mu.Unlock()
	return entry.dev, entry.found, nil
}
