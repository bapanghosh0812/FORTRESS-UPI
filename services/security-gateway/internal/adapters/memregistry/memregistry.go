// Package memregistry is a static device registry for development. Production
// reads binding records (device key, SIM-binding state, risk hold) from the
// identity service, cached for <= 5 s so revocations propagate quickly.
package memregistry

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"

	"github.com/fortress-upi/fortress/services/security-gateway/internal/devicesig"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

// Registry implements devicesig.Registry.
type Registry map[string]devicesig.Device

// Lookup implements devicesig.Registry.
func (r Registry) Lookup(_ context.Context, id string) (devicesig.Device, bool, error) {
	d, ok := r[id]
	return d, ok, nil
}

// Parse builds a registry from "deviceId:base64(spki);…".
func Parse(spec string) (Registry, error) {
	reg := Registry{}
	for _, entry := range strings.Split(spec, ";") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		id, b64, ok := strings.Cut(entry, ":")
		if !ok {
			return nil, errors.New("invalid DEVICE_KEYS entry: expected id:base64spki")
		}
		der, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			return nil, fmt.Errorf("invalid DEVICE_KEYS entry: %w", err)
		}
		pub, err := sig.ParseP256PublicKey(der)
		if err != nil {
			return nil, fmt.Errorf("invalid DEVICE_KEYS key: %w", err)
		}
		reg[id] = devicesig.Device{ID: id, PublicKey: pub, Active: true}
	}
	return reg, nil
}
