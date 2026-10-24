package server

import (
	"io"
	"log/slog"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/auth"
	"github.com/RavoxX/MCVoice/backend/go/internal/config"
	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"github.com/RavoxX/MCVoice/backend/go/internal/routing"
)

func hardeningServer() *Server {
	return New(&config.Config{MaxSessions: 1}, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

func admissionPlayer(id uint64, uuid string) *Session {
	return &Session{connID: id, ident: auth.Identity{UUID: uuid}, out: make(chan []byte, 128), done: make(chan struct{}),
		peer: routing.Peer{UUID: uuid, InWorld: true, Visible: map[string]struct{}{}}, presence: map[string]struct{}{}, groupsCap: true}
}

func TestForwardedIPRequiresTrustedPeerAndWalksChain(t *testing.T) {
	s := hardeningServer()
	s.cfg.TrustProxyHeaders = true
	s.cfg.TrustedProxyCIDRs = []netip.Prefix{netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("10.0.0.0/8")}
	for _, tc := range []struct{ peer, xff, want string }{
		{"203.0.113.2:123", "1.2.3.4", "203.0.113.2"},
		{"127.0.0.1:123", "1.2.3.4, 198.51.100.2", "198.51.100.2"},
		{"127.0.0.1:123", "198.51.100.2, 10.1.2.3", "198.51.100.2"},
		{"127.0.0.1:123", ", bad", "127.0.0.1"},
		{"127.0.0.1:123", "   ", "127.0.0.1"},
		{"127.0.0.1:123", "::ffff:198.51.100.2", "198.51.100.2"},
		{"127.0.0.1:123", "2001:db8::1", "2001:db8::1"},
	} {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = tc.peer
		r.Header.Set("X-Forwarded-For", tc.xff)
		if got := s.clientIP(r); got != tc.want {
			t.Fatalf("%+v: got %q", tc, got)
		}
	}
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "127.0.0.1:123"
	r.Header.Add("X-Forwarded-For", "1.2.3.4")
	r.Header.Add("X-Forwarded-For", "198.51.100.2")
	if got := s.clientIP(r); got != "198.51.100.2" {
		t.Fatal("repeated headers lost the proxy-appended peer", got)
	}
	r.Header.Del("X-Forwarded-For")
	r.Header.Add("X-Forwarded-For", "")
	r.Header.Add("X-Forwarded-For", "10.1.2.3")
	if got := s.clientIP(r); got != "127.0.0.1" {
		t.Fatal("empty header field disappeared from the trusted chain", got)
	}
}

func TestLimiterSaturationPreservesKnownBudgetAndBlocksUnknown(t *testing.T) {
	l := newIPLimiter(2)
	l.maxKeys = 1
	now := time.Now()
	if !l.allow("known", now) || !l.allow("known", now) || l.allow("known", now) {
		t.Fatal("known budget changed at capacity")
	}
	if l.blocked("unknown", now) || !l.allow("unknown", now) || !l.allow("another", now) || l.allow("third", now) || !l.blocked("third", now) {
		t.Fatal("overflow budget missing or bypassed")
	}
	if !l.allow("unknown", now.Add(time.Minute)) {
		t.Fatal("expired window did not recover")
	}
}

func TestReplacementAtCapacityBalancesMetricsAndRechecksModeration(t *testing.T) {
	s := hardeningServer()
	uuid := "00000000-0000-4000-8000-000000000001"
	old := admissionPlayer(1, uuid)
	if code := s.register(old); code != "" {
		t.Fatal(code)
	}
	old.hasAddr = true
	s.metrics.ActiveVoiceSessions.Add(1)
	s.muted[uuid] = struct{}{} // represents a policy published after the earlier advisory auth check
	next := admissionPlayer(2, uuid)
	if code := s.register(next); code != "" {
		t.Fatal(code)
	}
	s.unregister(old)
	if s.metrics.ConnectedClients.Get() != 1 || s.metrics.ActiveVoiceSessions.Get() != 0 {
		t.Fatal("replacement leaked metrics")
	}
	if !next.adminMute || !next.peer.Muted {
		t.Fatal("stale admission lost moderation")
	}
	s.banned[uuid] = struct{}{}
	if code := s.register(admissionPlayer(3, uuid)); code != protocol.CodeBanned {
		t.Fatal(code)
	}
	if s.byUUID[uuid] != next {
		t.Fatal("banned admission replaced legitimate session")
	}
	if code := s.register(admissionPlayer(4, "other")); code != protocol.CodeServerFull {
		t.Fatal(code)
	}
	s.unregister(next)
	if s.metrics.ConnectedClients.Get() != 0 {
		t.Fatal("disconnect leaked metrics")
	}
}

func TestStalePositionAndOverflowDeltaDoNotConsumeCurrentState(t *testing.T) {
	s := hardeningServer()
	p := admissionPlayer(1, "00000000-0000-4000-8000-000000000001")
	p.peer.Epoch = 2
	if code := s.register(p); code != "" {
		t.Fatal(code)
	}
	now := time.Now()
	old, current := uint32(1), uint32(2)
	x := 1.0
	s.dispatch(p, &protocol.Pos{Epoch: &old, X: &x, Y: &x, Z: &x}, now, s.log)
	s.dispatch(p, &protocol.Pos{Epoch: &current, X: &x, Y: &x, Z: &x}, now.Add(time.Millisecond), s.log)
	if !p.peer.HasPos {
		t.Fatal("stale epoch spent position budget")
	}
	for i := 0; i < protocol.MaxPeerList; i++ {
		p.peer.Visible[string(rune(i+1024))] = struct{}{}
	}
	base, rev := uint32(0), uint32(1)
	s.dispatch(p, &protocol.PeersDelta{Epoch: &current, Base: &base, Rev: &rev, Add: []string{"extra"}}, now, s.log)
	if len(p.peer.Visible) != protocol.MaxPeerList || p.peersRev != 0 {
		t.Fatal("overflow partially committed")
	}
	if len(p.out) != 1 {
		t.Fatal("overflow did not return an error")
	}
}

func TestPendingAdmissionIsReleasedOnUpgradeFailure(t *testing.T) {
	s := hardeningServer()
	for i := 0; i < 300; i++ {
		r := httptest.NewRequest("GET", "http://localhost/v1/control", nil)
		r.RemoteAddr = "127.0.0.1:123"
		s.HandleControl(httptest.NewRecorder(), r)
	}
	if len(s.pendingAuth) != 0 {
		t.Fatal("failed upgrades leaked admission slots")
	}
}

func TestReplyCounterExhaustionClosesWithoutNonceWrap(t *testing.T) {
	s := hardeningServer()
	p := admissionPlayer(1, "00000000-0000-4000-8000-000000000001")
	p.cur.sendCounter = ^uint64(0)
	s.reply(p, protocol.TypePong, 0)
	if p.closeCode != "key_exhausted" || p.cur.sendCounter != ^uint64(0) {
		t.Fatal("reply counter wrapped or session remained open")
	}
}

func TestAuthenticatedMaximumCounterDoesNotBlockNextDatagram(t *testing.T) {
	s := hardeningServer()
	s.cfg.MaxSessions = 2
	p := admissionPlayer(1, "00000000-0000-4000-8000-000000000001")
	other := admissionPlayer(2, "00000000-0000-4000-8000-000000000002")
	for _, sess := range []*Session{p, other} {
		sess.cur = newKeySlot(0, time.Hour, s.now())
		if code := s.register(sess); code != "" {
			t.Fatal(code)
		}
	}
	packet := func(sess *Session, counter uint64) []byte {
		return sess.cur.aead.Seal(nil, protocol.DirC2S, protocol.Header{Type: protocol.TypePing, ConnectionID: sess.connID, Counter: counter}, make([]byte, 8))
	}
	from := netip.MustParseAddrPort("127.0.0.1:24455")
	done := make(chan struct{})
	go func() {
		scratch := &udpScratch{}
		s.handleDatagram(packet(p, ^uint64(0)-1), from, nil, scratch)
		bad := packet(p, ^uint64(0))
		bad[len(bad)-1] ^= 1
		s.handleDatagram(bad, from, nil, scratch)
		s.handleDatagram(packet(p, ^uint64(0)), from, nil, scratch)
		s.handleDatagram(packet(other, 1), from, nil, scratch)
		close(done)
	}()
	select {
	case <-done:
		if p.cur.window.Check(^uint64(0)) || other.cur.window.Check(1) {
			t.Fatal("authenticated packet or subsequent session did not progress")
		}
	case <-time.After(time.Second):
		t.Fatal("authenticated counter stalled UDP handling")
	}
}
