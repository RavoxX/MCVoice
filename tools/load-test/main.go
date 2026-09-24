// Command loadtest simulates many MCVoice clients against a backend (Rust or Go)
// without launching Minecraft: authentication, scopes, positions, visible-peer
// deltas, 50 Hz voice frames, movement, dimension changes, proxy sub-server
// switches and disconnects. It reports delivery, loss and relay latency.
//
//	go run . -url ws://127.0.0.1:8080/v1/control -clients 200 -duration 60s
package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"math/rand"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/pkg/conformance"
	"github.com/RavoxX/MCVoice/backend/go/pkg/voiceclient"
)

type options struct {
	url          string
	udpHost      string
	clients      int
	groupSize    int
	talkers      float64
	duration     time.Duration
	rampUp       time.Duration
	churn        float64
	dimChange    float64
	serverSwitch float64
	jsonOut      string
}

type stats struct {
	connected, failed    atomic.Int64
	sent, received       atomic.Int64
	expected             atomic.Int64
	dimChanges, switches atomic.Int64
	reconnects           atomic.Int64
	wrongScopeDeliveries atomic.Int64
	mu                   sync.Mutex
	latencies            []float64
}

func (s *stats) latency(ms float64) {
	s.mu.Lock()
	if len(s.latencies) < 2_000_000 {
		s.latencies = append(s.latencies, ms)
	}
	s.mu.Unlock()
}

// player is one simulated client. Players of a group stand close together in
// the same scope and report each other as visible, like a real crowd.
type player struct {
	c       *voiceclient.Client
	group   int
	x, z    float64
	world   string
	network string
	talker  bool
	seq     uint16
}

func main() {
	var o options
	flag.StringVar(&o.url, "url", "ws://127.0.0.1:8080/v1/control", "backend control URL")
	flag.StringVar(&o.udpHost, "udp-host", "", "override voice UDP host (e.g. inside containers)")
	flag.IntVar(&o.clients, "clients", 100, "simulated clients")
	flag.IntVar(&o.groupSize, "group", 8, "players per proximity group")
	flag.Float64Var(&o.talkers, "talkers", 0.25, "fraction of players talking")
	flag.DurationVar(&o.duration, "duration", 30*time.Second, "test duration after ramp-up")
	flag.DurationVar(&o.rampUp, "ramp", 5*time.Second, "ramp-up time")
	flag.Float64Var(&o.churn, "churn", 0.002, "per-second probability that a player disconnects and reconnects")
	flag.Float64Var(&o.dimChange, "dim-change", 0.005, "per-second probability of a dimension change")
	flag.Float64Var(&o.serverSwitch, "server-switch", 0.003, "per-second probability of a proxy sub-server switch")
	flag.StringVar(&o.jsonOut, "json", "", "write the summary as JSON to this file")
	flag.Parse()
	if err := run(o); err != nil {
		fmt.Fprintln(os.Stderr, "loadtest:", err)
		os.Exit(1)
	}
}

func uuid() string {
	b := make([]byte, 16)
	rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func dial(o options, name, id string) (*voiceclient.Client, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return voiceclient.Dial(ctx, voiceclient.Options{ControlURL: o.url, Username: name, UUID: id, UDPHost: o.udpHost})
}

func run(o options) error {
	st := &stats{}
	players := make([]*player, o.clients)
	var mu sync.Mutex // guards group membership while players move around
	groups := (o.clients + o.groupSize - 1) / o.groupSize
	net := conformance.Net1

	connect := func(i int) error {
		id := uuid()
		c, err := dial(o, fmt.Sprintf("lt%d", i), id)
		if err != nil {
			st.failed.Add(1)
			return err
		}
		st.connected.Add(1)
		g := i % groups
		p := &player{c: c, group: g, x: float64(g*200) + rand.Float64()*6, z: rand.Float64() * 6, world: conformance.Overworld,
			network: net, talker: rand.Float64() < o.talkers}
		if _, err := c.Scope(true, p.network, p.world); err != nil {
			return err
		}
		_ = c.Pos(p.x, 64, p.z)
		mu.Lock()
		players[i] = p
		mu.Unlock()
		return nil
	}

	fmt.Printf("ramping up %d clients over %v ...\n", o.clients, o.rampUp)
	var wg sync.WaitGroup
	for i := 0; i < o.clients; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			time.Sleep(time.Duration(float64(o.rampUp) * float64(i) / float64(o.clients)))
			_ = connect(i)
		}(i)
	}
	wg.Wait()
	fmt.Printf("connected %d, failed %d\n", st.connected.Load(), st.failed.Load())

	// visible peers: everyone in the same group, scope and world
	refreshPeers := func() {
		mu.Lock()
		defer mu.Unlock()
		for _, p := range players {
			if p == nil {
				continue
			}
			var vis []string
			for _, q := range players {
				if q != nil && q != p && q.group == p.group && q.world == p.world && q.network == p.network {
					vis = append(vis, q.c.Session.PlayerUUID)
				}
			}
			_ = p.c.Peers(vis)
		}
	}
	refreshPeers()
	time.Sleep(time.Second)

	// receivers: measure latency from the embedded send timestamp and check scope
	byUUID := map[string]*player{}
	mu.Lock()
	for _, p := range players {
		if p != nil {
			byUUID[p.c.Session.PlayerUUID] = p
		}
	}
	mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), o.duration)
	defer cancel()
	for _, p := range players {
		if p == nil {
			continue
		}
		go func(p *player) {
			for {
				select {
				case <-ctx.Done():
					return
				case f := <-p.c.Relays():
					st.received.Add(1)
					if len(f.Payload) >= 8 {
						sentNs := int64(binary.BigEndian.Uint64(f.Payload))
						st.latency(float64(f.Received.UnixNano()-sentNs) / 1e6)
					}
					mu.Lock()
					s := byUUID[f.Sender]
					if s != nil && (s.world != p.world || s.group != p.group) {
						st.wrongScopeDeliveries.Add(1) // tolerated only within the settle window after a move
					}
					mu.Unlock()
				}
			}
		}(p)
	}

	// voice: 50 Hz frames from talkers; 60-byte payload with the send timestamp
	tick := time.NewTicker(20 * time.Millisecond)
	defer tick.Stop()
	posTick := time.NewTicker(200 * time.Millisecond)
	defer posTick.Stop()
	eventTick := time.NewTicker(time.Second)
	defer eventTick.Stop()
	payload := make([]byte, 60)
	start := time.Now()
	fmt.Printf("running for %v ...\n", o.duration)
loop:
	for {
		select {
		case <-ctx.Done():
			break loop
		case <-tick.C:
			mu.Lock()
			for _, p := range players {
				if p == nil || !p.talker {
					continue
				}
				binary.BigEndian.PutUint64(payload, uint64(time.Now().UnixNano()))
				p.seq++
				if p.c.SendVoice(p.seq, uint32(p.seq)*960, 0, 0, payload) == nil {
					st.sent.Add(1)
					n := 0
					for _, q := range players {
						if q != nil && q != p && q.group == p.group && q.world == p.world {
							n++
						}
					}
					st.expected.Add(int64(n))
				}
			}
			mu.Unlock()
		case <-posTick.C:
			mu.Lock()
			for _, p := range players {
				if p == nil {
					continue
				}
				p.x += (rand.Float64() - 0.5) * 0.8
				p.z += (rand.Float64() - 0.5) * 0.8
				_ = p.c.Pos(p.x, 64, p.z)
			}
			mu.Unlock()
		case <-eventTick.C:
			changed := false
			mu.Lock()
			for i, p := range players {
				if p == nil {
					continue
				}
				r := rand.Float64()
				switch {
				case r < o.dimChange:
					if p.world == conformance.Overworld {
						p.world = conformance.Nether
					} else {
						p.world = conformance.Overworld
					}
					_, _ = p.c.Scope(true, p.network, p.world)
					_ = p.c.Pos(p.x, 64, p.z)
					st.dimChanges.Add(1)
					changed = true
				case r < o.dimChange+o.serverSwitch:
					p.group = rand.Intn(groups) // proxy switch: new sub-server population
					p.x = float64(p.group*200) + rand.Float64()*6
					_, _ = p.c.Scope(true, p.network, p.world)
					_ = p.c.Pos(p.x, 64, p.z)
					st.switches.Add(1)
					changed = true
				case r < o.dimChange+o.serverSwitch+o.churn:
					old := p
					players[i] = nil
					go func(i int) {
						old.c.Close()
						if connect(i) == nil {
							st.reconnects.Add(1)
							mu.Lock()
							byUUID[players[i].c.Session.PlayerUUID] = players[i]
							mu.Unlock()
						}
					}(i)
					changed = true
				}
			}
			mu.Unlock()
			if changed {
				refreshPeers()
			}
		}
	}
	elapsed := time.Since(start)
	time.Sleep(300 * time.Millisecond)
	mu.Lock()
	for _, p := range players {
		if p != nil {
			p.c.Close()
		}
	}
	mu.Unlock()
	return report(o, st, elapsed)
}

func percentile(s []float64, p float64) float64 {
	if len(s) == 0 {
		return math.NaN()
	}
	i := int(math.Ceil(p/100*float64(len(s)))) - 1
	if i < 0 {
		i = 0
	}
	return s[i]
}

func report(o options, st *stats, elapsed time.Duration) error {
	st.mu.Lock()
	lat := append([]float64(nil), st.latencies...)
	st.mu.Unlock()
	sort.Float64s(lat)
	exp := st.expected.Load()
	rec := st.received.Load()
	delivery := 0.0
	if exp > 0 {
		delivery = float64(rec) / float64(exp)
	}
	sum := map[string]any{
		"clients": o.clients, "connected": st.connected.Load(), "connect_failures": st.failed.Load(),
		"duration_s": elapsed.Seconds(), "frames_sent": st.sent.Load(), "relays_received": rec, "relays_expected": exp,
		"delivery_ratio": delivery, "sent_pps": float64(st.sent.Load()) / elapsed.Seconds(),
		"relayed_pps":    float64(rec) / elapsed.Seconds(),
		"latency_ms_p50": percentile(lat, 50), "latency_ms_p95": percentile(lat, 95), "latency_ms_p99": percentile(lat, 99),
		"dimension_changes": st.dimChanges.Load(), "server_switches": st.switches.Load(), "reconnects": st.reconnects.Load(),
		"cross_scope_deliveries": st.wrongScopeDeliveries.Load(),
	}
	b, _ := json.MarshalIndent(sum, "", "  ")
	fmt.Println(string(b))
	if o.jsonOut != "" {
		if err := os.WriteFile(o.jsonOut, b, 0o644); err != nil {
			return err
		}
	}
	if st.connected.Load() == 0 {
		return fmt.Errorf("no client could connect")
	}
	return nil
}
