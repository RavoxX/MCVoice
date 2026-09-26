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
	PositionStaleMs int64
}

func DefaultConfig() Config {
	return Config{NormalRange: 48, WhisperRange: 8, MaxRange: 96, DistanceSlack: 4, PositionStaleMs: 3000}
}

// Peer is the routing-relevant state of one session.
type Peer struct {
	UUID          string
	Authenticated bool
	UDPVerified   bool
	InWorld       bool
	WorldID       string // dimension key the client reported (spec 6.3)
	Attested      string // companion-plugin attested scope "<network>/<subserver>", empty if none (spec 6.3.1)
	Epoch         uint32
	HasPos        bool
	X, Y, Z       float64
	PosAtMs       int64
	Visible       map[string]struct{}
	Muted         bool // self-muted or admin-muted
	Deafened      bool
}

// CompatibleScopes is check 5: same dimension, and the same attested sub-server when both are
// attested. The address a player joined through (network_id) is deliberately not compared (spec 6.3).
func CompatibleScopes(s, r *Peer) bool {
	return s.WorldID == r.WorldID && (s.Attested == "" || r.Attested == "" || s.Attested == r.Attested)
}

// MutuallyVisible is checks 7-8: each side's own world tracks the other player (mandatory, spec 8).
func MutuallyVisible(s, r *Peer) bool {
	_, rSeesS := r.Visible[s.UUID]
	_, sSeesR := s.Visible[r.UUID]
	return rSeesS && sSeesR
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
	if !CompatibleScopes(s, r) || !r.InWorld || !r.UDPVerified || !r.Authenticated || r.Deafened || !r.freshPos(cfg, now) {
		return false
	}
	if !MutuallyVisible(s, r) {
		return false
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
