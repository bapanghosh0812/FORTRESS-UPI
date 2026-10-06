// Package routing maps public API prefixes to mesh upstreams.
package routing

import (
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"
)

// Route sends requests whose path starts with Prefix to Upstream. Public
// routes (device binding) skip device-signature verification because the
// device has no bound key yet; they carry their own attestation proof.
type Route struct {
	Prefix   string
	Upstream *url.URL
	Public   bool
}

// Parse reads "prefix|upstream[|public];…", e.g.
// "/v1/bind/|http://identity:8080|public;/v1/offline/|http://offline-mesh:8080".
func Parse(spec string) ([]Route, error) {
	var routes []Route
	for _, entry := range strings.Split(spec, ";") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		parts := strings.Split(entry, "|")
		if len(parts) < 2 || len(parts) > 3 || !strings.HasPrefix(parts[0], "/v1/") {
			return nil, fmt.Errorf("routing: bad entry %q", entry)
		}
		u, err := url.Parse(parts[1])
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return nil, fmt.Errorf("routing: bad upstream in %q", entry)
		}
		public := len(parts) == 3
		if public && parts[2] != "public" {
			return nil, fmt.Errorf("routing: unknown flag in %q", entry)
		}
		routes = append(routes, Route{Prefix: parts[0], Upstream: u, Public: public})
	}
	if len(routes) == 0 {
		return nil, errors.New("routing: no routes configured")
	}
	// Longest prefix first so more specific routes win.
	sort.Slice(routes, func(i, j int) bool { return len(routes[i].Prefix) > len(routes[j].Prefix) })
	return routes, nil
}

// Match returns the route for path, if any.
func Match(routes []Route, path string) (Route, bool) {
	for _, r := range routes {
		if strings.HasPrefix(path, r.Prefix) {
			return r, true
		}
	}
	return Route{}, false
}
