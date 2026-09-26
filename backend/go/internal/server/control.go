package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"

	"github.com/RavoxX/MCVoice/backend/go/internal/auth"
	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"github.com/RavoxX/MCVoice/backend/go/internal/routing"
)

const (
	helloTimeout = 10 * time.Second
	authTimeout  = 30 * time.Second
)

func (s *Server) clientIP(r *http.Request) string {
	if s.cfg.TrustProxyHeaders {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			first, _, _ := strings.Cut(xff, ",")
			return strings.TrimSpace(first)
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (s *Server) voiceHost(r *http.Request) string {
	if s.cfg.PublicHostname != "" {
		return s.cfg.PublicHostname
	}
	host, _, err := net.SplitHostPort(r.Host)
	if err != nil {
		return r.Host
	}
	return host
}

type ctlConn struct {
	ws  *websocket.Conn
	ctx context.Context
}

func (c *ctlConn) write(b []byte) error {
	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()
	return c.ws.Write(ctx, websocket.MessageText, b)
}

func (c *ctlConn) read(timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(c.ctx, timeout)
	defer cancel()
	t, b, err := c.ws.Read(ctx)
	if err != nil {
		return nil, err
	}
	if t != websocket.MessageText {
		return nil, errBinary
	}
	return b, nil
}

var errBinary = errors.New("binary frame")

func (c *ctlConn) fail(code, msg string) {
	_ = c.write(errorFrame(code, msg, true))
	status := websocket.StatusPolicyViolation
	if code == protocol.CodeInternal {
		status = websocket.StatusInternalError
	}
	_ = c.ws.Close(status, code)
}

// HandleControl serves GET /v1/control.
func (s *Server) HandleControl(w http.ResponseWriter, r *http.Request) {
	ip := s.clientIP(r)
	now := s.now()
	if s.shutdown.Load() {
		http.Error(w, "shutting down", http.StatusServiceUnavailable)
		return
	}
	if !s.connectLimiter.allow(ip, now) || s.authFailLimiter.blocked(ip, now) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		return
	}
	ws.SetReadLimit(protocol.MaxControlFrame + 1024)
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	c := &ctlConn{ws: ws, ctx: ctx}
	log := s.log.With("category", "control", "ip", s.ipTag(ip))

	// --- hello ---
	frame, err := c.read(helloTimeout)
	if err != nil {
		c.fail(protocol.CodeBadMessage, "expected hello")
		return
	}
	msg, cerr := protocol.ParseControl(frame)
	if cerr != nil {
		c.fail(cerr.Code, cerr.Message)
		return
	}
	hello, ok := msg.(*protocol.Hello)
	if !ok {
		c.fail(protocol.CodeBadMessage, "first message must be hello")
		return
	}
	challenge := hex.EncodeToString(randomBytes(16))
	methods := []string{s.cfg.AuthMode}
	if err := c.write(marshal(protocol.HelloOK{Type: "hello_ok", Protocol: protocol.Version{Major: protocol.Major, Minor: protocol.Minor},
		Server:       protocol.ServerInfo{Name: "mcvoice-backend", Version: Version, Implementation: "go"},
		Capabilities: protocol.ServerCapabilities, Auth: protocol.AuthInfo{Methods: methods, Challenge: challenge}})); err != nil {
		return
	}
	challengeAt := s.now()
	_ = hello

	// --- auth / resume ---
	frame, err = c.read(authTimeout)
	if err != nil {
		c.fail(protocol.CodeAuthRequired, "expected auth")
		return
	}
	msg, cerr = protocol.ParseControl(frame)
	if cerr != nil {
		c.fail(cerr.Code, cerr.Message)
		return
	}
	var ident auth.Identity
	switch m := msg.(type) {
	case *protocol.Auth:
		if *m.Method != s.cfg.AuthMode {
			c.fail(protocol.CodeUnsupportedAuthMethod, "auth method not enabled")
			return
		}
		if s.now().Sub(challengeAt) > 60*time.Second {
			c.fail(protocol.CodeAuthFailed, "challenge expired")
			return
		}
		switch *m.Method {
		case "mojang":
			actx, acancel := context.WithTimeout(ctx, 10*time.Second)
			ident, err = s.mojang.Verify(actx, *m.Username, challenge)
			acancel()
		case "offline":
			ident, err = auth.Identity{UUID: *m.UUID, Username: *m.Username}, nil
		}
	case *protocol.Resume:
		ident, err = auth.VerifyResume(s.cfg.JWTSigningSecret, *m.ResumeToken, s.now())
		if err != nil {
			s.metrics.AuthFailures.Inc()
			s.authFailLimiter.allow(ip, s.now())
			c.fail(protocol.CodeSessionExpired, "resume token invalid or expired")
			return
		}
	default:
		c.fail(protocol.CodeAuthRequired, "authenticate first")
		return
	}
	if err != nil {
		s.metrics.AuthFailures.Inc()
		s.authFailLimiter.allow(ip, s.now())
		log.Info("authentication failed", "err", err)
		c.fail(protocol.CodeAuthFailed, "could not verify Minecraft session")
		return
	}
	if s.isBanned(ident.UUID) {
		c.fail(protocol.CodeBanned, "banned")
		return
	}

	now = s.now()
	ttl := time.Duration(s.cfg.KeyRotationSecs) * time.Second * 2
	sess := &Session{id: newSessionID(), connID: newConnID(), ident: ident,
		out: make(chan []byte, 128), done: make(chan struct{}),
		presence:    map[string]struct{}{},
		voiceBucket: newBucket(s.cfg.RateVoicePerSec, s.cfg.RateVoiceBurst),
		ctlBucket:   newBucket(s.cfg.RateControlPerSec, s.cfg.RateControlBurst)}
	sess.uuidB, _ = protocol.UUIDBytes(ident.UUID)
	sess.cur = newKeySlot(0, ttl, now)
	sess.adminMute = s.isAdminMuted(ident.UUID)
	sess.peer = routing.Peer{UUID: ident.UUID, Authenticated: true, Visible: map[string]struct{}{}, Muted: sess.adminMute}
	sess.lastSeen.Store(now.UnixMilli())
	if !s.register(sess) {
		c.fail(protocol.CodeServerFull, "server full")
		return
	}
	defer s.unregister(sess)
	log = log.With("session", sess.id)
	log.Info("session started", "player", ident.UUID, "client", hello.Client.Version, "minecraft", hello.Client.Minecraft, "loader", hello.Client.Loader)

	if err := c.write(marshal(protocol.SessionMsg{Type: "session", SessionID: sess.id, PlayerUUID: ident.UUID, Username: ident.Username,
		ResumeToken: auth.IssueResume(s.cfg.JWTSigningSecret, ident, time.Duration(s.cfg.ResumeTokenTTL)*time.Second, now),
		Voice: protocol.VoiceInfo{Host: s.voiceHost(r), Port: s.voicePort(), ConnectionID: hexU64(sess.connID),
			KeyID: int(sess.cur.id), Key: sess.cur.b64(), KeyExpiresIn: int(ttl.Seconds())},
		Config: protocol.SessionConfig{NormalRange: s.cfg.NormalRange, WhisperRange: s.cfg.WhisperRange, MaxRange: s.cfg.MaxRange,
			Codec: "opus", SampleRate: 48000, FrameMs: 20, HeartbeatInterval: 5, PositionHzMax: 10}})); err != nil {
		return
	}

	// writer
	go func() {
		for {
			select {
			case <-sess.done:
				cancel()
				return
			case <-ctx.Done():
				return
			case b := <-sess.out:
				if err := c.write(b); err != nil {
					cancel()
					return
				}
			}
		}
	}()

	// key rotation
	go func() {
		t := time.NewTicker(time.Duration(s.cfg.KeyRotationSecs) * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-sess.done:
				return
			case <-t.C:
				sess.umu.Lock()
				k := sess.rotate(ttl, s.now())
				sess.umu.Unlock()
				sess.send(marshal(protocol.KeyMsg{Type: "key", KeyID: int(k.id), Key: k.b64(), KeyExpiresIn: int(ttl.Seconds())}))
			}
		}
	}()

	timeout := time.Duration(s.cfg.SessionTimeoutSec) * time.Second
	rateStrikes, strikeWindow := 0, s.now()
	for {
		frame, err := c.read(timeout)
		if err != nil {
			select {
			case <-sess.done:
				_ = c.ws.Close(websocket.StatusPolicyViolation, sess.closeCode)
			default:
				if errors.Is(err, errBinary) {
					c.fail(protocol.CodeBadMessage, "binary frames are not allowed")
				} else {
					_ = c.ws.Close(websocket.StatusNormalClosure, "timeout")
				}
			}
			log.Info("session ended", "reason", closeReason(err, sess))
			return
		}
		s.metrics.ControlMessages.Inc()
		now := s.now()
		sess.lastSeen.Store(now.UnixMilli())
		if now.Sub(strikeWindow) > 10*time.Second {
			rateStrikes, strikeWindow = 0, now
		}
		if !sess.ctlBucket.allow(now) {
			rateStrikes++
			if rateStrikes > 200 {
				c.fail(protocol.CodeRateLimited, "sustained control flood")
				return
			}
			sess.send(errorFrame(protocol.CodeRateLimited, "slow down", false))
			continue
		}
		msg, cerr := protocol.ParseControl(frame)
		if cerr != nil {
			if cerr.Code == protocol.CodeUnknownMessage {
				sess.send(errorFrame(cerr.Code, cerr.Message, false))
				continue
			}
			c.fail(cerr.Code, cerr.Message)
			return
		}
		if done := s.dispatch(sess, msg, now, log); done {
			_ = c.ws.Close(websocket.StatusNormalClosure, "bye")
			return
		}
	}
}

func closeReason(err error, sess *Session) string {
	select {
	case <-sess.done:
		return sess.closeCode
	default:
	}
	if websocket.CloseStatus(err) != -1 {
		return "client_closed"
	}
	return "timeout_or_network"
}

func (s *Server) voicePort() int {
	if s.cfg.PublicVoicePort != 0 {
		return s.cfg.PublicVoicePort
	}
	return s.UDPAddr().Port
}

func hexU64(v uint64) string {
	var b [8]byte
	for i := 7; i >= 0; i-- {
		b[i] = byte(v)
		v >>= 8
	}
	return hex.EncodeToString(b[:])
}

// dispatch applies one authenticated control message. Returns true on bye.
func (s *Server) dispatch(sess *Session, msg any, now time.Time, log interface {
	Debug(string, ...any)
}) bool {
	switch m := msg.(type) {
	case *protocol.Hello, *protocol.Auth, *protocol.Resume:
		sess.send(errorFrame(protocol.CodeBadMessage, "already authenticated", false))
	case *protocol.Scope:
		s.mu.Lock()
		if sess.hasScope && *m.Epoch <= sess.peer.Epoch {
			s.mu.Unlock()
			sess.send(errorFrame(protocol.CodeStaleEpoch, "epoch must increase", false))
			return false
		}
		sess.hasScope = true
		sess.lastPos = time.Time{} // the first position of a new epoch is always accepted
		sess.peer.Epoch = *m.Epoch
		sess.peer.InWorld = *m.InWorld
		sess.peer.HasPos = false
		sess.peer.Visible = map[string]struct{}{}
		sess.peersRev = 0
		sess.presence = map[string]struct{}{}
		sess.peer.WorldID, sess.peer.Attested = "", ""
		if *m.InWorld {
			// network_id is validated by the parser but never used for routing (spec 6.3)
			sess.peer.WorldID = *m.WorldID
			if m.Attestation != nil {
				if a, ok := auth.VerifyAttestation(s.cfg.AttestationKeys, *m.Attestation, sess.ident.UUID, now); ok {
					sess.peer.Attested = a.Network + "/" + a.Subserver
				} else {
					log.Debug("ignored invalid scope attestation")
				}
			}
		}
		s.mu.Unlock()
	case *protocol.Pos:
		if now.Sub(sess.lastPos) < 90*time.Millisecond {
			return false // above position_hz_max, dropped silently
		}
		sess.lastPos = now
		s.mu.Lock()
		if *m.Epoch == sess.peer.Epoch && sess.peer.InWorld {
			sess.peer.HasPos, sess.peer.X, sess.peer.Y, sess.peer.Z = true, *m.X, *m.Y, *m.Z
			sess.peer.PosAtMs = now.UnixMilli()
		}
		s.mu.Unlock()
		if s.cfg.LogPositions {
			log.Debug("position", "epoch", *m.Epoch, "x", *m.X, "y", *m.Y, "z", *m.Z)
		}
	case *protocol.Peers:
		s.mu.Lock()
		if *m.Epoch == sess.peer.Epoch {
			v := make(map[string]struct{}, len(m.Full))
			for _, u := range m.Full {
				if u != sess.ident.UUID {
					v[u] = struct{}{}
				}
			}
			sess.peer.Visible, sess.peersRev = v, *m.Rev
		}
		s.mu.Unlock()
	case *protocol.PeersDelta:
		s.mu.Lock()
		if *m.Epoch != sess.peer.Epoch {
			s.mu.Unlock()
			return false
		}
		if *m.Base != sess.peersRev || *m.Rev <= *m.Base {
			ep := sess.peer.Epoch
			s.mu.Unlock()
			sess.send(marshal(protocol.PeersResync{Type: "peers_resync", Epoch: ep}))
			return false
		}
		for _, u := range m.Remove {
			delete(sess.peer.Visible, u)
		}
		for _, u := range m.Add {
			if u != sess.ident.UUID && len(sess.peer.Visible) < protocol.MaxPeerList {
				sess.peer.Visible[u] = struct{}{}
			}
		}
		sess.peersRev = *m.Rev
		s.mu.Unlock()
	case *protocol.State:
		s.mu.Lock()
		sess.selfMuted = *m.Muted
		sess.peer.Muted = sess.selfMuted || sess.adminMute
		sess.peer.Deafened = *m.Deafened
		s.mu.Unlock()
	case *protocol.Ping:
		sess.send(marshal(protocol.Pong{Type: "pong", Nonce: *m.Nonce, ServerTime: now.UnixMilli()}))
	case *protocol.Bye:
		return true
	}
	return false
}

// NetworkID computes the spec 6.3 network id for tests and tools.
func NetworkID(host string, port int) string {
	h := sha256.Sum256([]byte(strings.ToLower(host) + ":" + itoa(port)))
	return "n1:" + hex.EncodeToString(h[:])[:32]
}
