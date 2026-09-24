// Package metrics is a minimal Prometheus text-format exporter. Metric names
// are identical in the Rust backend (docs/backend-deployment.md#metrics).
package metrics

import (
	"fmt"
	"io"
	"math"
	"sort"
	"sync"
	"sync/atomic"
)

type Counter struct{ v atomic.Uint64 }

func (c *Counter) Inc()         { c.v.Add(1) }
func (c *Counter) Add(n uint64) { c.v.Add(n) }
func (c *Counter) Get() uint64  { return c.v.Load() }

type Gauge struct{ v atomic.Int64 }

func (g *Gauge) Add(n int64) { g.v.Add(n) }
func (g *Gauge) Set(n int64) { g.v.Store(n) }
func (g *Gauge) Get() int64  { return g.v.Load() }

// FloatGauge stores a float64.
type FloatGauge struct{ v atomic.Uint64 }

func (g *FloatGauge) Set(f float64) { g.v.Store(math.Float64bits(f)) }
func (g *FloatGauge) Get() float64  { return math.Float64frombits(g.v.Load()) }

// LabeledCounter is a counter family with one label.
type LabeledCounter struct {
	mu sync.Mutex
	m  map[string]*Counter
}

func (l *LabeledCounter) With(label string) *Counter {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.m == nil {
		l.m = map[string]*Counter{}
	}
	c, ok := l.m[label]
	if !ok {
		c = &Counter{}
		l.m[label] = c
	}
	return c
}

func (l *LabeledCounter) snapshot() map[string]uint64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make(map[string]uint64, len(l.m))
	for k, c := range l.m {
		out[k] = c.Get()
	}
	return out
}

// Histogram with fixed buckets (seconds).
type Histogram struct {
	bounds []float64
	counts []atomic.Uint64
	sumNs  atomic.Uint64
	total  atomic.Uint64
}

func NewHistogram(bounds []float64) *Histogram {
	return &Histogram{bounds: bounds, counts: make([]atomic.Uint64, len(bounds))}
}

func (h *Histogram) Observe(seconds float64) {
	for i, b := range h.bounds {
		if seconds <= b {
			h.counts[i].Add(1)
		}
	}
	h.sumNs.Add(uint64(seconds * 1e9))
	h.total.Add(1)
}

// Registry holds all backend metrics.
type Registry struct {
	Implementation string
	Version        string

	ConnectedClients    Gauge
	ActiveVoiceSessions Gauge
	PacketsReceived     Counter
	PacketsSent         Counter
	BytesReceived       Counter
	BytesSent           Counter
	PacketsDropped      LabeledCounter
	InvalidPackets      LabeledCounter
	AuthFailures        Counter
	ControlMessages     Counter
	RelayLatency        *Histogram
	VoiceJitter         FloatGauge
}

func New(impl, version string) *Registry {
	return &Registry{Implementation: impl, Version: version,
		RelayLatency: NewHistogram([]float64{0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05})}
}

func (r *Registry) Write(w io.Writer) {
	p := func(format string, a ...any) { fmt.Fprintf(w, format, a...) }
	p("# HELP mcvoice_build_info Backend implementation and version.\n# TYPE mcvoice_build_info gauge\n")
	p("mcvoice_build_info{implementation=%q,version=%q} 1\n", r.Implementation, r.Version)
	gauge := func(name, help string, v int64) {
		p("# HELP %s %s\n# TYPE %s gauge\n%s %d\n", name, help, name, name, v)
	}
	counter := func(name, help string, v uint64) {
		p("# HELP %s %s\n# TYPE %s counter\n%s %d\n", name, help, name, name, v)
	}
	labeled := func(name, help, label string, l *LabeledCounter) {
		p("# HELP %s %s\n# TYPE %s counter\n", name, help, name)
		s := l.snapshot()
		keys := make([]string, 0, len(s))
		for k := range s {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			p("%s{%s=%q} %d\n", name, label, k, s[k])
		}
	}
	gauge("mcvoice_connected_clients", "Control connections with an authenticated session.", r.ConnectedClients.Get())
	gauge("mcvoice_active_voice_sessions", "Sessions with a verified UDP voice path.", r.ActiveVoiceSessions.Get())
	counter("mcvoice_packets_received_total", "UDP datagrams received.", r.PacketsReceived.Get())
	counter("mcvoice_packets_sent_total", "UDP datagrams sent.", r.PacketsSent.Get())
	counter("mcvoice_bytes_received_total", "UDP bytes received.", r.BytesReceived.Get())
	counter("mcvoice_bytes_sent_total", "UDP bytes sent.", r.BytesSent.Get())
	labeled("mcvoice_packets_dropped_total", "Authenticated datagrams that were not relayed.", "reason", &r.PacketsDropped)
	labeled("mcvoice_invalid_packets_total", "Datagrams rejected as invalid.", "reason", &r.InvalidPackets)
	counter("mcvoice_auth_failures_total", "Failed control authentications.", r.AuthFailures.Get())
	counter("mcvoice_control_messages_total", "Control messages received.", r.ControlMessages.Get())
	p("# HELP mcvoice_voice_jitter_seconds Average interarrival jitter of active voice streams.\n# TYPE mcvoice_voice_jitter_seconds gauge\n")
	p("mcvoice_voice_jitter_seconds %g\n", r.VoiceJitter.Get())
	h := r.RelayLatency
	p("# HELP mcvoice_relay_latency_seconds Time from datagram receipt to relay send completion.\n# TYPE mcvoice_relay_latency_seconds histogram\n")
	for i, b := range h.bounds {
		p("mcvoice_relay_latency_seconds_bucket{le=\"%g\"} %d\n", b, h.counts[i].Load())
	}
	p("mcvoice_relay_latency_seconds_bucket{le=\"+Inf\"} %d\n", h.total.Load())
	p("mcvoice_relay_latency_seconds_sum %g\n", float64(h.sumNs.Load())/1e9)
	p("mcvoice_relay_latency_seconds_count %d\n", h.total.Load())
}
