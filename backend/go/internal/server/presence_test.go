package server

import (
	"fmt"
	"testing"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/metrics"
	"github.com/RavoxX/MCVoice/backend/go/internal/routing"
)

func presenceFixture(n int) *Server {
	s := &Server{byConn: make(map[uint64]*Session), byUUID: make(map[string]*Session), now: time.Now,
		metrics: metrics.New("test", "test")}
	for i := 0; i < n; i++ {
		u := fmt.Sprintf("00000000-0000-4000-8000-%012x", i)
		p := &Session{connID: uint64(i + 1), out: make(chan []byte, 8), done: make(chan struct{}),
			presence: make(map[string]struct{}), peer: routing.Peer{UUID: u, InWorld: true, UDPVerified: true,
				WorldID: "world", Epoch: 1, Visible: make(map[string]struct{})}}
		s.byConn[p.connID], s.byUUID[u] = p, p
	}
	for i := 0; i < n; i++ {
		p := s.byConn[uint64(i+1)]
		for j := (i / 10) * 10; j < (i/10)*10+10 && j < n; j++ {
			if j != i {
				p.peer.Visible[s.byConn[uint64(j+1)].peer.UUID] = struct{}{}
			}
		}
	}
	s.presenceTick()
	for _, p := range s.byConn {
		<-p.out
	}
	return s
}

func BenchmarkPresenceStable(b *testing.B) {
	for _, n := range []int{100, 1000} {
		b.Run(fmt.Sprint(n), func(b *testing.B) {
			s := presenceFixture(n)
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				s.presenceTick()
			}
		})
	}
}

func TestPresenceRemovalUsesEveryRoutingCheck(t *testing.T) {
	for _, change := range []string{"world", "visible", "verified", "in-world"} {
		t.Run(change, func(t *testing.T) {
			s := presenceFixture(10)
			p, o := s.byConn[1], s.byConn[2]
			switch change {
			case "world":
				o.peer.WorldID = "other"
			case "visible":
				delete(o.peer.Visible, p.peer.UUID)
			case "verified":
				o.peer.UDPVerified = false
			case "in-world":
				o.peer.InWorld = false
			}
			s.presenceTick()
			if _, present := p.presence[o.peer.UUID]; present {
				t.Fatal("stale presence retained")
			}
			if len(p.out) != 1 {
				t.Fatal("missing presence diff")
			}
		})
	}
}

func TestStablePresenceDoesNotNeedExclusiveHubAccess(t *testing.T) {
	s := presenceFixture(10)
	s.mu.RLock()
	done := make(chan struct{})
	go func() { s.presenceTick(); close(done) }()
	var timedOut bool
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		timedOut = true
	}
	s.mu.RUnlock()
	<-done
	if timedOut {
		t.Fatal("stable presence waited for exclusive hub access")
	}
}

func TestJitterSweepDoesNotHoldHubWhileWaitingForUDP(t *testing.T) {
	s := presenceFixture(10)
	for _, p := range s.byConn {
		p.umu.Lock()
	}
	done := make(chan struct{})
	go func() { s.updateJitterGauge(); close(done) }()
	var timedOut bool
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		timedOut = true
	}
	for _, p := range s.byConn {
		p.umu.Unlock()
	}
	<-done
	if timedOut {
		t.Fatal("diagnostic sweep queued behind busy packet locks")
	}
}
