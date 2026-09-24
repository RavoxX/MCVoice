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

const Version = "0.1.0"

// Server is the combined control service and voice relay.
type Server struct {
	cfg     *config.Config
	rcfg    routing.Config
	log     *slog.Logger
	metrics *metrics.Registry
	mojang  *auth.MojangVerifier

	mu      sync.RWMutex
	byConn  map[uint64]*Session
	byUUID  map[string]*Session
	buckets map[string]map[*Session]struct{}

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
		cfg: cfg,
		rcfg: routing.Config{NormalRange: cfg.NormalRange, WhisperRange: cfg.WhisperRange, MaxRange: cfg.MaxRange,
			DistanceSlack: cfg.DistanceSlack, RequireMutual: cfg.RequireMutual, PositionStaleMs: 3000},
		log:             log,
		metrics:         metrics.New("go", Version),
		mojang:          auth.NewMojangVerifier(cfg.MojangSessionURL),
		byConn:          map[uint64]*Session{},
		byUUID:          map[string]*Session{},
		buckets:         map[string]map[*Session]struct{}{},
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
func (s *Server) register(sess *Session) (ok bool) {
	s.mu.Lock()
	if len(s.byConn) >= s.cfg.MaxSessions {
		s.mu.Unlock()
		return false
	}
	old := s.byUUID[sess.ident.UUID]
	if old != nil {
		s.removeLocked(old)
	}
	s.byConn[sess.connID] = sess
	s.byUUID[sess.ident.UUID] = sess
	s.mu.Unlock()
	if old != nil {
		old.send(errorFrame(protocol.CodeSessionReplaced, "replaced by a newer session", true))
		old.close(protocol.CodeSessionReplaced)
	}
	s.metrics.ConnectedClients.Add(1)
	return true
}

func (s *Server) unregister(sess *Session) {
	s.mu.Lock()
	removed := s.byConn[sess.connID] == sess
	if removed {
		s.removeLocked(sess)
	}
	s.mu.Unlock()
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
	delete(s.byConn, sess.connID)
	if s.byUUID[sess.ident.UUID] == sess {
		delete(s.byUUID, sess.ident.UUID)
	}
	s.leaveBucketLocked(sess)
}

func (s *Server) leaveBucketLocked(sess *Session) {
	if sess.peer.ScopeKey == "" {
		return
	}
	if b := s.buckets[sess.peer.ScopeKey]; b != nil {
		delete(b, sess)
		if len(b) == 0 {
			delete(s.buckets, sess.peer.ScopeKey)
		}
	}
	sess.peer.ScopeKey = ""
}

func (s *Server) joinBucketLocked(sess *Session, key string) {
	sess.peer.ScopeKey = key
	b := s.buckets[key]
	if b == nil {
		b = map[*Session]struct{}{}
		s.buckets[key] = b
	}
	b[sess] = struct{}{}
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
	s.bansMu.Lock()
	s.banned, s.muted, s.bansMtime = banned, muted, st.ModTime()
	s.bansMu.Unlock()
	s.log.Info("bans loaded", "category", "moderation", "banned", len(banned), "muted", len(muted))

	s.mu.Lock()
	var kick []*Session
	for _, sess := range s.byConn {
		_, m := muted[sess.ident.UUID]
		sess.adminMute = m
		sess.peer.Muted = sess.selfMuted || m
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
	type upd struct {
		sess *Session
		msg  []byte
	}
	var updates []upd
	s.mu.Lock()
	for _, r := range s.byConn {
		next := map[string]struct{}{}
		if r.peer.InWorld {
			for u := range r.peer.Visible {
				if o := s.byUUID[u]; o != nil && o.peer.InWorld && o.peer.UDPVerified && o.peer.ScopeKey == r.peer.ScopeKey {
					next[u] = struct{}{}
				}
			}
		}
		var add, remove []string
		for u := range next {
			if _, ok := r.presence[u]; !ok {
				add = append(add, u)
			}
		}
		for u := range r.presence {
			if _, ok := next[u]; !ok {
				remove = append(remove, u)
			}
		}
		if len(add) == 0 && len(remove) == 0 {
			continue
		}
		sort.Strings(add)
		sort.Strings(remove)
		r.presence = next
		if add == nil {
			add = []string{}
		}
		if remove == nil {
			remove = []string{}
		}
		updates = append(updates, upd{r, marshal(protocol.PresenceMsg{Type: "presence", Epoch: r.peer.Epoch, Add: add, Remove: remove})})
	}
	s.mu.Unlock()
	for _, u := range updates {
		u.sess.send(u.msg)
	}
}

// updateJitterGauge averages per-session jitter estimates of recently active streams.
func (s *Server) updateJitterGauge() {
	now := s.now()
	var sum float64
	var n int
	s.mu.RLock()
	for _, sess := range s.byConn {
		sess.umu.Lock()
		if now.Sub(sess.lastVoice) < 5*time.Second && sess.haveTransit {
			sum += sess.jitter
			n++
		}
		sess.umu.Unlock()
	}
	s.mu.RUnlock()
	if n > 0 {
		s.metrics.VoiceJitter.Set(sum / float64(n))
	} else {
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
