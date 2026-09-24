// Package routing implements the normative backend routing decision
// (spec section 8). It is a pure function over session snapshots so that it
// can be verified against protocol/test-vectors/routing.json.
package routing

import "github.com/RavoxX/MCVoice/backend/go/internal/protocol"

type Config struct {
	NormalRange     float64
	WhisperRange    float64
	MaxRange        float64
	DistanceSlack   float64
	RequireMutual   bool
	PositionStaleMs int64
}

func DefaultConfig() Config {
	return Config{NormalRange: 48, WhisperRange: 8, MaxRange: 96, DistanceSlack: 4, RequireMutual: true, PositionStaleMs: 3000}
}

// Peer is the routing-relevant state of one session.
type Peer struct {
	UUID          string
	Authenticated bool
	UDPVerified   bool
	InWorld       bool
	ScopeKey      string
	Epoch         uint32
	HasPos        bool
	X, Y, Z       float64
	PosAtMs       int64
	Visible       map[string]struct{}
	Muted         bool // self-muted or admin-muted
	Deafened      bool
}

// ScopeKey builds the scope key used to bucket sessions.
func ScopeKey(networkID, attested, worldID string) string {
	if attested != "" {
		return "attested:" + attested + "|" + worldID
	}
	return networkID + "|" + worldID
}

func (p *Peer) freshPos(cfg *Config, now int64) bool {
	return p.HasPos && now-p.PosAtMs <= cfg.PositionStaleMs
}

// Range returns the effective routing range for a voice mode.
func (cfg *Config) Range(mode byte) float64 {
	r := cfg.NormalRange
	if mode == protocol.ModeWhisper {
		r = cfg.WhisperRange
	}
	if r > cfg.MaxRange {
		r = cfg.MaxRange
	}
	return r
}

// SenderEligible applies checks 1-3 for the sender.
func SenderEligible(cfg *Config, now int64, s *Peer, packetEpoch uint32) bool {
	return s.Authenticated && s.UDPVerified && !s.Muted && s.InWorld && packetEpoch == s.Epoch && s.freshPos(cfg, now)
}

// Deliver applies checks 5-9 for one recipient.
func Deliver(cfg *Config, now int64, s, r *Peer, mode byte) bool {
	if r == s || r.UUID == s.UUID {
		return false
	}
	if r.ScopeKey != s.ScopeKey || !r.InWorld || !r.UDPVerified || !r.Authenticated || r.Deafened || !r.freshPos(cfg, now) {
		return false
	}
	if _, ok := r.Visible[s.UUID]; !ok {
		return false
	}
	if cfg.RequireMutual {
		if _, ok := s.Visible[r.UUID]; !ok {
			return false
		}
	}
	limit := cfg.Range(mode) + cfg.DistanceSlack
	dx, dy, dz := s.X-r.X, s.Y-r.Y, s.Z-r.Z
	return dx*dx+dy*dy+dz*dz <= limit*limit
}

// Route returns the recipients among candidates for a voice packet from s.
func Route(cfg *Config, now int64, s *Peer, packetEpoch uint32, mode byte, candidates []*Peer) []*Peer {
	if !SenderEligible(cfg, now, s, packetEpoch) {
		return nil
	}
	var out []*Peer
	for _, r := range candidates {
		if Deliver(cfg, now, s, r, mode) {
			out = append(out, r)
		}
	}
	return out
}
