package server

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"runtime"
	"sync"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"github.com/RavoxX/MCVoice/backend/go/internal/routing"
)

var bufPool = sync.Pool{New: func() any { b := make([]byte, 0, 2048); return &b }}

// ListenUDP binds the voice socket.
func (s *Server) ListenUDP() error {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(s.cfg.VoiceBindAddress, itoa(s.cfg.VoiceUDPPort)))
	if err != nil {
		return err
	}
	c, err := net.ListenUDP("udp", addr)
	if err != nil {
		return err
	}
	_ = c.SetReadBuffer(4 << 20)
	_ = c.SetWriteBuffer(4 << 20)
	s.udp = c
	return nil
}

// UDPAddr returns the bound voice address (useful for tests using port 0).
func (s *Server) UDPAddr() *net.UDPAddr { return s.udp.LocalAddr().(*net.UDPAddr) }

// ServeUDP runs the relay workers until ctx is cancelled.
func (s *Server) ServeUDP(ctx context.Context) {
	workers := s.cfg.UDPWorkers
	if workers <= 0 {
		workers = runtime.GOMAXPROCS(0)
	}
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			s.udpWorker(ctx)
		}()
	}
	<-ctx.Done()
	_ = s.udp.Close()
	wg.Wait()
}

func (s *Server) udpWorker(ctx context.Context) {
	buf := make([]byte, protocol.MaxDatagram+1)
	pt := make([]byte, 0, protocol.MaxDatagram)
	for {
		n, from, err := s.udp.ReadFromUDPAddrPort(buf)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		s.metrics.PacketsReceived.Inc()
		s.metrics.BytesReceived.Add(uint64(n))
		s.handleDatagram(buf[:n], from, pt[:0])
	}
}

func (s *Server) invalid(reason string) { s.metrics.InvalidPackets.With(reason).Inc() }
func (s *Server) dropped(reason string) { s.metrics.PacketsDropped.With(reason).Inc() }

func (s *Server) handleDatagram(dg []byte, from netip.AddrPort, pt []byte) {
	start := time.Now()
	h, err := protocol.ParseHeader(dg, protocol.DirC2S)
	if err != nil {
		s.invalid(protocol.ClassOf(err))
		return
	}
	s.mu.RLock()
	sess := s.byConn[h.ConnectionID]
	s.mu.RUnlock()
	if sess == nil {
		s.invalid("unknown_session")
		return
	}
	now := s.now()

	sess.umu.Lock()
	key := sess.keyFor(h.KeyID, now)
	if key == nil {
		sess.umu.Unlock()
		s.invalid("unknown_key")
		return
	}
	if !key.window.Check(h.Counter) {
		sess.umu.Unlock()
		s.dropped("replay")
		return
	}
	pt, err = key.aead.Open(pt, protocol.DirC2S, h, dg)
	if err != nil {
		sess.umu.Unlock()
		s.invalid("auth_failed")
		return
	}
	if err := protocol.ParsePlaintext(h.Type, pt); err != nil {
		sess.umu.Unlock()
		s.invalid(protocol.ClassOf(err))
		return
	}
	newTop := key.window.Update(h.Counter)
	if key == &sess.cur && !sess.sendWithNew && sess.prev.valid {
		sess.sendWithNew = true // client proved it has the rotated key
	}

	switch h.Type {
	case protocol.TypeHello:
		uid, ct, _ := protocol.ParseHello(pt)
		if uid != sess.uuidB {
			sess.umu.Unlock()
			s.invalid("uuid_mismatch")
			return
		}
		first := !sess.hasAddr
		sess.addr, sess.hasAddr = from, true
		sendOK := !sess.udpOKSent
		sess.udpOKSent = true
		sess.umu.Unlock()
		if first {
			s.metrics.ActiveVoiceSessions.Add(1)
			s.mu.Lock()
			sess.peer.UDPVerified = true
			s.mu.Unlock()
		}
		s.reply(sess, protocol.TypeHelloAck, ct)
		if sendOK {
			sess.send(marshal(protocol.Simple{Type: "udp_ok"}))
		}
		return
	case protocol.TypePing:
		if !sess.hasAddr {
			sess.umu.Unlock()
			s.dropped("no_hello")
			return
		}
		if newTop {
			sess.addr = from
		}
		ct := binary.BigEndian.Uint64(pt)
		sess.umu.Unlock()
		s.reply(sess, protocol.TypePong, ct)
		return
	case protocol.TypeVoice:
		if !sess.hasAddr {
			sess.umu.Unlock()
			s.dropped("no_hello")
			return
		}
		if newTop {
			sess.addr = from // NAT rebinding, only on a fresh authenticated counter
		}
		if !sess.voiceBucket.allow(now) {
			sess.umu.Unlock()
			s.dropped("rate_limited")
			return
		}
		v, _ := protocol.ParseVoice(pt)
		s.trackJitter(sess, v.Timestamp, now)
		sess.umu.Unlock()
		s.relayVoice(sess, v, now)
		s.metrics.RelayLatency.Observe(time.Since(start).Seconds())
	}
}

// trackJitter maintains an RFC 3550 interarrival jitter estimate (caller holds umu).
func (s *Server) trackJitter(sess *Session, ts uint32, now time.Time) {
	arrival := float64(now.UnixNano()) / 1e9
	transit := arrival - float64(ts)/48000.0
	if sess.haveTransit && now.Sub(sess.lastVoice) < time.Second {
		d := transit - sess.lastTransit
		if d < 0 {
			d = -d
		}
		if d < 1 { // ignore timestamp wrap / talk-spurt restarts
			sess.jitter += (d - sess.jitter) / 16
		}
	}
	sess.lastTransit, sess.haveTransit, sess.lastVoice = transit, true, now
}

func (s *Server) reply(sess *Session, t byte, ct uint64) {
	var p [8]byte
	binary.BigEndian.PutUint64(p[:], ct)
	bp := bufPool.Get().(*[]byte)
	sess.umu.Lock()
	k := sess.sendKey(s.now())
	k.sendCounter++
	out := k.aead.Seal((*bp)[:0], protocol.DirS2C, protocol.Header{Type: t, KeyID: k.id, ConnectionID: sess.connID, Counter: k.sendCounter}, p[:])
	addr := sess.addr
	sess.umu.Unlock()
	s.write(out, addr)
	*bp = out
	bufPool.Put(bp)
}

func (s *Server) write(b []byte, addr netip.AddrPort) {
	if _, err := s.udp.WriteToUDPAddrPort(b, addr); err == nil {
		s.metrics.PacketsSent.Inc()
		s.metrics.BytesSent.Add(uint64(len(b)))
	}
}

type target struct {
	sess  *Session
	epoch uint32
	group bool // relayed with mode 2 (spec 8.1)
}

var targetPool = sync.Pool{New: func() any { t := make([]target, 0, 16); return &t }}

func (s *Server) relayVoice(sender *Session, v protocol.Voice, now time.Time) {
	nowMs := now.UnixMilli()
	tp := targetPool.Get().(*[]target)
	targets := (*tp)[:0]

	s.mu.RLock()
	group := routing.GroupApplies(&sender.peer, v.Mode, v.Flags)
	proximity := v.Mode != protocol.ModeGroup && routing.SenderEligible(&s.rcfg, nowMs, &sender.peer, v.Epoch)
	if !group && !proximity {
		reason := "not_routable"
		if sender.peer.InWorld && v.Epoch != sender.peer.Epoch {
			reason = "stale_epoch"
		}
		s.mu.RUnlock()
		s.dropped(reason)
		*tp = targets
		targetPool.Put(tp)
		return
	}
	if group {
		// spec 8.1: every other member of the sender's group, relayed with mode 2
		if g := s.groups[sender.peer.Group]; g != nil {
			for _, r := range g.members {
				if routing.DeliverGroup(&sender.peer, &r.peer) {
					targets = append(targets, target{r, r.peer.Epoch, true})
				}
			}
		}
	}
	if proximity {
		// candidates are the players the sender's own world tracks (mutual visibility is mandatory)
		for u := range sender.peer.Visible {
			if r := s.byUUID[u]; r != nil && routing.DeliverProximity(&s.rcfg, nowMs, &sender.peer, &r.peer, v.Mode, group) {
				targets = append(targets, target{r, r.peer.Epoch, false})
			}
		}
	}
	s.mu.RUnlock()

	if len(targets) > 0 {
		bp := bufPool.Get().(*[]byte)
		ptb := bufPool.Get().(*[]byte)
		for _, t := range targets {
			rv := v
			if t.group {
				rv.Mode, rv.Flags = protocol.ModeGroup, v.Flags&^protocol.FlagGroup
			}
			plain := protocol.AppendRelay((*ptb)[:0], sender.uuidB, t.epoch, rv)
			t.sess.umu.Lock()
			if !t.sess.hasAddr {
				t.sess.umu.Unlock()
				continue
			}
			k := t.sess.sendKey(now)
			k.sendCounter++
			out := k.aead.Seal((*bp)[:0], protocol.DirS2C,
				protocol.Header{Type: protocol.TypeVoiceRelay, KeyID: k.id, ConnectionID: t.sess.connID, Counter: k.sendCounter}, plain)
			addr := t.sess.addr
			t.sess.umu.Unlock()
			s.write(out, addr)
			*bp, *ptb = out, plain
		}
		bufPool.Put(bp)
		bufPool.Put(ptb)
	}
	for i := range targets {
		targets[i] = target{}
	}
	*tp = targets[:0]
	targetPool.Put(tp)
}

func itoa(n int) string {
	var b [20]byte
	i := len(b)
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
