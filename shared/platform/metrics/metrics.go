// Package metrics exposes Prometheus text-format metrics without third-party
// dependencies: per-route request counters by status class and a latency
// histogram whose buckets bracket the 15 ms p99 SLO. KEDA scales on
// rate(fortress_http_requests_total[30s]).
package metrics

import (
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Bucket upper bounds in seconds; 0.015 is the SLO line.
var bounds = [...]float64{0.0005, 0.001, 0.0025, 0.005, 0.0075, 0.010, 0.015, 0.025, 0.05, 0.1, 0.25, 1}

type routeStats struct {
	byClass   [6]atomic.Uint64 // index = status/100
	buckets   [len(bounds) + 1]atomic.Uint64
	sumMicros atomic.Uint64
	count     atomic.Uint64
}

// Registry is safe for concurrent use.
type Registry struct {
	mu     sync.RWMutex
	routes map[string]*routeStats
}

// NewRegistry returns an empty registry.
func NewRegistry() *Registry { return &Registry{routes: map[string]*routeStats{}} }

func (r *Registry) stats(route string) *routeStats {
	r.mu.RLock()
	s, ok := r.routes[route]
	r.mu.RUnlock()
	if ok {
		return s
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if s, ok = r.routes[route]; !ok {
		s = &routeStats{}
		r.routes[route] = s
	}
	return s
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// Instrument records metrics for next under a fixed route label. Route labels
// are static strings, never raw paths, to keep cardinality bounded.
func (r *Registry) Instrument(route string, next http.Handler) http.Handler {
	s := r.stats(route)
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, req)
		elapsed := time.Since(start)

		class := rec.status / 100
		if class < 1 || class > 5 {
			class = 5
		}
		s.byClass[class].Add(1)
		s.count.Add(1)
		s.sumMicros.Add(uint64(elapsed.Microseconds())) //nolint:gosec // durations are non-negative
		secs := elapsed.Seconds()
		i := sort.SearchFloat64s(bounds[:], secs)
		s.buckets[i].Add(1)
	})
}

// Handler serves the Prometheus exposition format.
func (r *Registry) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		r.mu.RLock()
		names := make([]string, 0, len(r.routes))
		for n := range r.routes {
			names = append(names, n)
		}
		r.mu.RUnlock()
		sort.Strings(names)

		var b strings.Builder
		b.WriteString("# TYPE fortress_http_requests_total counter\n")
		for _, n := range names {
			s := r.stats(n)
			for c := 1; c <= 5; c++ {
				fmt.Fprintf(&b, "fortress_http_requests_total{route=%q,code=\"%dxx\"} %d\n", n, c, s.byClass[c].Load())
			}
		}
		b.WriteString("# TYPE fortress_http_request_duration_seconds histogram\n")
		for _, n := range names {
			s := r.stats(n)
			var cum uint64
			for i, le := range bounds {
				cum += s.buckets[i].Load()
				fmt.Fprintf(&b, "fortress_http_request_duration_seconds_bucket{route=%q,le=\"%g\"} %d\n", n, le, cum)
			}
			cum += s.buckets[len(bounds)].Load()
			fmt.Fprintf(&b, "fortress_http_request_duration_seconds_bucket{route=%q,le=\"+Inf\"} %d\n", n, cum)
			fmt.Fprintf(&b, "fortress_http_request_duration_seconds_sum{route=%q} %g\n", n, float64(s.sumMicros.Load())/1e6)
			fmt.Fprintf(&b, "fortress_http_request_duration_seconds_count{route=%q} %d\n", n, s.count.Load())
		}
		w.Header().Set("Content-Type", "text/plain; version=0.0.4")
		_, _ = w.Write([]byte(b.String()))
	})
}
