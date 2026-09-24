package routing

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

func vectorPath(t *testing.T) string {
	dir, _ := os.Getwd()
	for i := 0; i < 8; i++ {
		p := filepath.Join(dir, "protocol", "test-vectors", "routing.json")
		if _, err := os.Stat(p); err == nil {
			return p
		}
		dir = filepath.Dir(dir)
	}
	t.Fatal("routing.json not found")
	return ""
}

type vecSession struct {
	UUID          string     `json:"uuid"`
	Authenticated bool       `json:"authenticated"`
	UDPVerified   bool       `json:"udp_verified"`
	InWorld       bool       `json:"in_world"`
	NetworkID     string     `json:"network_id"`
	WorldID       string     `json:"world_id"`
	Epoch         uint32     `json:"epoch"`
	Pos           *[]float64 `json:"pos"`
	PosAtMs       int64      `json:"pos_at_ms"`
	Visible       []string   `json:"visible"`
	Muted         bool       `json:"muted"`
	Deafened      bool       `json:"deafened"`
}

func TestRoutingVectors(t *testing.T) {
	b, err := os.ReadFile(vectorPath(t))
	if err != nil {
		t.Fatal(err)
	}
	var vs struct {
		Cases []struct {
			Name   string `json:"name"`
			Config struct {
				NormalRange   float64 `json:"normal_range"`
				WhisperRange  float64 `json:"whisper_range"`
				MaxRange      float64 `json:"max_range"`
				DistanceSlack float64 `json:"distance_slack"`
				Mutual        bool    `json:"require_mutual_visibility"`
				PosStale      int64   `json:"position_stale_ms"`
			} `json:"config"`
			NowMs    int64        `json:"now_ms"`
			Sessions []vecSession `json:"sessions"`
			Packet   struct {
				Sender string `json:"sender"`
				Epoch  uint32 `json:"epoch"`
				Mode   byte   `json:"mode"`
			} `json:"packet"`
			Expect []string `json:"expect"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(b, &vs); err != nil {
		t.Fatal(err)
	}
	if len(vs.Cases) < 10 {
		t.Fatal("too few routing vectors")
	}
	for _, c := range vs.Cases {
		t.Run(c.Name, func(t *testing.T) {
			cfg := Config{c.Config.NormalRange, c.Config.WhisperRange, c.Config.MaxRange, c.Config.DistanceSlack, c.Config.Mutual, c.Config.PosStale}
			var peers []*Peer
			var sender *Peer
			for _, s := range c.Sessions {
				p := &Peer{UUID: s.UUID, Authenticated: s.Authenticated, UDPVerified: s.UDPVerified, InWorld: s.InWorld,
					ScopeKey: ScopeKey(s.NetworkID, "", s.WorldID), Epoch: s.Epoch, PosAtMs: s.PosAtMs,
					Visible: map[string]struct{}{}, Muted: s.Muted, Deafened: s.Deafened}
				if s.Pos != nil {
					p.HasPos, p.X, p.Y, p.Z = true, (*s.Pos)[0], (*s.Pos)[1], (*s.Pos)[2]
				}
				for _, v := range s.Visible {
					p.Visible[v] = struct{}{}
				}
				if s.UUID == c.Packet.Sender {
					sender = p
				}
				peers = append(peers, p)
			}
			got := []string{}
			for _, r := range Route(&cfg, c.NowMs, sender, c.Packet.Epoch, c.Packet.Mode, peers) {
				got = append(got, r.UUID)
			}
			sort.Strings(got)
			if len(got) != len(c.Expect) {
				t.Fatalf("got %v want %v", got, c.Expect)
			}
			for i := range got {
				if got[i] != c.Expect[i] {
					t.Fatalf("got %v want %v", got, c.Expect)
				}
			}
		})
	}
}
