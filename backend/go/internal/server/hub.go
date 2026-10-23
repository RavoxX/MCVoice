package server

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/auth"
	"github.com/RavoxX/MCVoice/backend/go/internal/config"
	"github.com/RavoxX/MCVoice/backend/go/internal/metrics"
	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"github.com/RavoxX/MCVoice/backend/go/internal/routing"
)

const Version = "0.1.11"

// Server is the combined control service and voice relay.
type Server struct {
	epoch         time.Time
	pendingAuth   chan struct{}
	groupFailures *ipLimiter
	cfg           *config.Config
	rcfg          routing.Config
	log           *slog.Logger
	metrics       *metrics.Registry
	mojang        *auth.MojangVerifier

	mu     sync.RWMutex
	byConn map[uint64]*Session
	byUUID map[string]*Session
	groups map[string]*group
	outbox []outMsg // group messages queued under mu, sent by unlockAndFlush

	bansMu    sync.RWMutex
	banned    map[string]struct{}
	muted     map[string]struct{}
	bansMtime time.Time

	connectLimiter  *ipLimiter
	authFailLimiter *ipLimiter

	udp      *net.UDPConn
	ready    atomic.Bool
	shutdown atomic.Bool
	now      func() time.Time
}

func New(cfg *config.Config, log *slog.Logger) *Server {
	return &Server{
		epoch: time.Now(), pendingAuth: make(chan struct{}, 256), groupFailures: newIPLimiter(passwordFailsPerMin),
		cfg: cfg,
		rcfg: routing.Config{NormalRange: cfg.NormalRange, WhisperRange: cfg.WhisperRange, MaxRange: cfg.MaxRange,
			DistanceSlack: cfg.DistanceSlack, PositionStaleMs: 3000},
		log:             log,
		metrics:         metrics.New("go", Version),
		mojang:          auth.NewMojangVerifier(cfg.MojangSessionURL),
		byConn:          map[uint64]*Session{},
		byUUID:          map[string]*Session{},
		groups:          map[string]*group{},
		banned:          map[string]struct{}{},
		muted:           map[string]struct{}{},
		connectLimiter:  newIPLimiter(cfg.RateConnectPerMin),
		authFailLimiter: newIPLimiter(cfg.RateAuthFailPerMin),
		now:             time.Now,
	}
}

func (s *Server) Metrics() *metrics.Registry { return s.metrics }

// ipTag is a keyed hash of an address so logs never contain raw IPs.
func (s *Server) ipTag(ip string) string {
	m := hmac.New(sha256.New, s.cfg.SessionSecret)
	m.Write([]byte(ip))
	return hex.EncodeToString(m.Sum(nil))[:12]
}

func marshal(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

func errorFrame(code, msg string, fatal bool) []byte {
	return marshal(protocol.ErrorMsg{Type: "error", Code: code, Message: msg, Fatal: fatal})
}

// register adds a session, replacing any older session for the same player.
func (s *Server) register(sess *Session) string {
	s.mu.Lock()
	s.bansMu.RLock()
	if _, banned := s.banned[sess.ident.UUID]; banned {
		s.bansMu.RUnlock()
		s.mu.Unlock()
		return protocol.CodeBanned
	}
	_, sess.adminMute = s.muted[sess.ident.UUID]
	sess.peer.Muted = sess.selfMuted || sess.adminMute
	s.bansMu.RUnlock()
	if len(s.byConn) >= s.cfg.MaxSessions && s.byUUID[sess.ident.UUID] == nil {
		s.mu.Unlock()
		return protocol.CodeServerFull
	}
	old := s.byUUID[sess.ident.UUID]
	if old != nil {
		s.removeLocked(old)
	}
	s.byConn[sess.connID] = sess
	s.byUUID[sess.ident.UUID] = sess
	s.unlockAndFlush()
	if old != nil {
		s.metrics.ConnectedClients.Add(-1)
		old.umu.Lock()
		if old.hasAddr {
			s.metrics.ActiveVoiceSessions.Add(-1)
			old.hasAddr = false
		}
		old.umu.Unlock()
		old.send(errorFrame(protocol.CodeSessionReplaced, "replaced by a newer session", true))
		old.close(protocol.CodeSessionReplaced)
	}
	s.metrics.ConnectedClients.Add(1)
	return ""
}

func (s *Server) unregister(sess *Session) {
	s.mu.Lock()
	removed := s.byConn[sess.connID] == sess
	if removed {
		s.removeLocked(sess)
	}
	s.unlockAndFlush()
	if removed {
		s.metrics.ConnectedClients.Add(-1)
		sess.umu.Lock()
		if sess.hasAddr {
			s.metrics.ActiveVoiceSessions.Add(-1)
			sess.hasAddr = false
		}
		sess.umu.Unlock()
	}
}

// removeLocked drops sess from all indexes (caller holds s.mu).
func (s *Server) removeLocked(sess *Session) {
	s.leaveGroupLocked(sess, "") // disconnected: out of the group (spec 6.12)
	delete(s.byConn, sess.connID)
	if s.byUUID[sess.ident.UUID] == sess {
		delete(s.byUUID, sess.ident.UUID)
	}
}

func (s *Server) isBanned(uuid string) bool {
	s.bansMu.RLock()
	defer s.bansMu.RUnlock()
	_, ok := s.banned[uuid]
	return ok
}

func (s *Server) isAdminMuted(uuid string) bool {
	s.bansMu.RLock()
	defer s.bansMu.RUnlock()
	_, ok := s.muted[uuid]
	return ok
}

// reloadBans (re)reads BANS_FILE: {"banned":[uuid...],"muted":[uuid...]}.
func (s *Server) reloadBans() {
	if s.cfg.BansFile == "" {
		return
	}
	st, err := os.Stat(s.cfg.BansFile)
	if err != nil {
		s.log.Warn("bans file unreadable", "category", "moderation", "err", err)
		return
	}
	if !st.ModTime().After(s.bansMtime) {
		return
	}
	b, err := os.ReadFile(s.cfg.BansFile)
	if err != nil {
		return
	}
	var f struct {
		Banned []string `json:"banned"`
		Muted  []string `json:"muted"`
	}
	if err := json.Unmarshal(b, &f); err != nil {
		s.log.Warn("bans file invalid", "category", "moderation", "err", err)
		return
	}
	banned, muted := map[string]struct{}{}, map[string]struct{}{}
	for _, u := range f.Banned {
		if protocol.ValidUUID(u) {
			banned[u] = struct{}{}
		}
	}
	for _, u := range f.Muted {
		if protocol.ValidUUID(u) {
			muted[u] = struct{}{}
		}
	}
	s.mu.Lock()
	s.bansMu.Lock()
	s.banned, s.muted, s.bansMtime = banned, muted, st.ModTime()
	s.bansMu.Unlock()
	s.log.Info("bans loaded", "category", "moderation", "banned", len(banned), "muted", len(muted))

	var kick []*Session
	for _, sess := range s.byConn {
		_, m := muted[sess.ident.UUID]
		_, b := banned[sess.ident.UUID]
		sess.adminMute = m || b
		sess.peer.Muted = sess.selfMuted || sess.adminMute
		if _, b := banned[sess.ident.UUID]; b {
			kick = append(kick, sess)
		}
	}
	s.mu.Unlock()
	for _, sess := range kick {
		sess.send(errorFrame(protocol.CodeBanned, "banned", true))
		sess.close(protocol.CodeBanned)
	}
}

// presenceTick recomputes presence (spec 6.7) for every session and sends diffs.
func (s *Server) presenceTick() {
	s.groupTick(s.now())
	type upd struct {
		sess *Session
		msg  protocol.PresenceMsg
	}
	var updates []upd
	s.mu.RLock()
	for _, r := range s.byConn {
		if !s.hasPresenceDelta(r) {
			continue
		}
		updates = append(updates, upd{r, protocol.PresenceMsg{Type: "presence", Epoch: r.peer.Epoch}})
	}
	s.mu.RUnlock()
	if len(updates) > 0 {
		s.mu.Lock()
		n := 0
		for _, u := range updates {
			if s.byConn[u.sess.connID] != u.sess {
				continue
			}
			// Revalidate after taking the write lock; never commit an old routing snapshot.
			add, remove := s.presenceDelta(u.sess)
			if len(add) == 0 && len(remove) == 0 {
				continue
			}
			for _, id := range remove {
				delete(u.sess.presence, id)
			}
			for _, id := range add {
				u.sess.presence[id] = struct{}{}
			}
			u.msg.Epoch, u.msg.Add, u.msg.Remove = u.sess.peer.Epoch, add, remove
			updates[n] = u
			n++
		}
		updates = updates[:n]
		s.mu.Unlock()
	}
	for _, u := range updates {
		sort.Strings(u.msg.Add)
		sort.Strings(u.msg.Remove)
		if u.msg.Add == nil {
			u.msg.Add = []string{}
		}
		if u.msg.Remove == nil {
			u.msg.Remove = []string{}
		}
		u.sess.send(marshal(u.msg))
	}
}

// Caller holds mu. Detect changes without allocating lists discarded by revalidation.
func (s *Server) hasPresenceDelta(r *Session) bool {
	present := func(u string) bool {
		o := s.byUUID[u]
		return r.peer.InWorld && o != nil && o.peer.InWorld && o.peer.UDPVerified && routing.CompatibleScopes(&o.peer, &r.peer) && routing.MutuallyVisible(&o.peer, &r.peer)
	}
	for u := range r.peer.Visible {
		if _, ok := r.presence[u]; !ok && present(u) {
			return true
		}
	}
	for u := range r.presence {
		if !present(u) {
			return true
		}
	}
	return false
}

// Caller holds mu. Stable presence allocates nothing and leaves packet readers unblocked.
func (s *Server) presenceDelta(r *Session) (add, remove []string) {
	present := func(u string) bool {
		o := s.byUUID[u]
		return r.peer.InWorld && o != nil && o.peer.InWorld && o.peer.UDPVerified &&
			routing.CompatibleScopes(&o.peer, &r.peer) && routing.MutuallyVisible(&o.peer, &r.peer)
	}
	for u := range r.peer.Visible {
		if _, ok := r.presence[u]; !ok && present(u) {
			add = append(add, u)
		}
	}
	for u := range r.presence {
		if !present(u) {
			remove = append(remove, u)
		}
	}
	return
}

// updateJitterGauge averages per-session jitter estimates of recently active streams.
func (s *Server) updateJitterGauge() {
	now := s.now()
	var sum float64
	var n int
	busy := false
	s.mu.RLock()
	sessions := make([]*Session, 0, len(s.byConn))
	for _, sess := range s.byConn {
		sessions = append(sessions, sess)
	}
	s.mu.RUnlock()
	for _, sess := range sessions {
		if !sess.umu.TryLock() {
			busy = true
			continue // diagnostics must not queue behind packet encryption
		}
		if now.Sub(sess.lastVoice) < 5*time.Second && sess.haveTransit {
			sum += sess.jitter
			n++
		}
		sess.umu.Unlock()
	}
	if n > 0 {
		s.metrics.VoiceJitter.Set(sum / float64(n))
	} else if !busy {
		s.metrics.VoiceJitter.Set(0)
	}
}

// RotateAllKeys forces a voice key rotation for every session (tests, admin).
func (s *Server) RotateAllKeys() {
	ttl := time.Duration(s.cfg.KeyRotationSecs) * time.Second * 2
	s.mu.RLock()
	sessions := make([]*Session, 0, len(s.byConn))
	for _, sess := range s.byConn {
		sessions = append(sessions, sess)
	}
	s.mu.RUnlock()
	for _, sess := range sessions {
		sess.umu.Lock()
		k := sess.rotate(ttl, s.now())
		sess.umu.Unlock()
		sess.send(marshal(protocol.KeyMsg{Type: "key", KeyID: int(k.id), Key: k.b64(), KeyExpiresIn: int(ttl.Seconds())}))
	}
}
