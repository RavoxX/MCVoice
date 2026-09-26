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
	NetworkID     string     `json:"network_id"` // informational: routing must ignore it
	WorldID       string     `json:"world_id"`
	Attested      string     `json:"attested"`
	Group         string     `json:"group"`
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
				PosStale      int64   `json:"position_stale_ms"`
			} `json:"config"`
			NowMs    int64        `json:"now_ms"`
			Sessions []vecSession `json:"sessions"`
			Packet   struct {
				Sender string `json:"sender"`
				Epoch  uint32 `json:"epoch"`
				Mode   byte   `json:"mode"`
				Flags  byte   `json:"flags"`
			} `json:"packet"`
			Expect      []string `json:"expect"`
			ExpectGroup []string `json:"expect_group"`
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
			cfg := Config{c.Config.NormalRange, c.Config.WhisperRange, c.Config.MaxRange, c.Config.DistanceSlack, c.Config.PosStale}
			var peers []*Peer
			var sender *Peer
			for _, s := range c.Sessions {
				p := &Peer{UUID: s.UUID, Authenticated: s.Authenticated, UDPVerified: s.UDPVerified, InWorld: s.InWorld,
					WorldID: s.WorldID, Attested: s.Attested, Group: s.Group, Epoch: s.Epoch, PosAtMs: s.PosAtMs,
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
			routed := Route(&cfg, c.NowMs, sender, c.Packet.Epoch, c.Packet.Mode, c.Packet.Flags, peers)
			check := func(what string, l []*Peer, want []string) {
				got := []string{}
				for _, r := range l {
					got = append(got, r.UUID)
				}
				sort.Strings(got)
				if len(got) != len(want) {
					t.Fatalf("%s: got %v want %v", what, got, want)
				}
				for i := range got {
					if got[i] != want[i] {
						t.Fatalf("%s: got %v want %v", what, got, want)
					}
				}
			}
			check("proximity", routed.Proximity, c.Expect)
			check("group", routed.Group, c.ExpectGroup)
		})
	}
}
