package server

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/auth"
	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"github.com/RavoxX/MCVoice/backend/go/internal/routing"
)

const keyGrace = 30 * time.Second

type keySlot struct {
	valid       bool
	id          byte
	raw         []byte
	aead        *protocol.AEAD
	window      protocol.ReplayWindow
	sendCounter uint64
	retireAt    time.Time // zero for the current key
	expiresAt   time.Time
}

// Session is one authenticated client.
type Session struct {
	id     string
	connID uint64
	ident  auth.Identity
	uuidB  [16]byte

	out       chan []byte
	closeOnce sync.Once
	done      chan struct{}
	closeCode string

	// Routing state, guarded by Hub.mu.
	peer      routing.Peer
	networkID string
	worldID   string
	attested  string
	peersRev  uint32
	presence  map[string]struct{}
	selfMuted bool
	adminMute bool

	// UDP state, guarded by umu.
	umu         sync.Mutex
	cur, prev   keySlot
	sendWithNew bool // client proved possession of cur
	addr        netip.AddrPort
	hasAddr     bool
	udpOKSent   bool
	voiceBucket tokenBucket
	jitter      float64 // RFC 3550 interarrival jitter, seconds
	lastTransit float64
	haveTransit bool
	lastVoice   time.Time

	// Control state, only touched by the control goroutine.
	ctlBucket tokenBucket
	lastPos   time.Time

	lastSeen atomic.Int64
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

func newSessionID() string { return "s_" + hex.EncodeToString(randomBytes(12)) }

func newConnID() uint64 {
	for {
		v := binary.BigEndian.Uint64(randomBytes(8))
		if v != 0 {
			return v
		}
	}
}

func newKeySlot(id byte, ttl time.Duration, now time.Time) keySlot {
	raw := randomBytes(protocol.KeyLen)
	a, err := protocol.NewAEAD(raw)
	if err != nil {
		panic(err)
	}
	return keySlot{valid: true, id: id, raw: raw, aead: a, expiresAt: now.Add(ttl)}
}

func (k *keySlot) b64() string { return base64.StdEncoding.EncodeToString(k.raw) }

// send queues a control message; if the client cannot keep up the session is closed.
func (s *Session) send(msg []byte) {
	select {
	case <-s.done:
	case s.out <- msg:
	default:
		s.close("slow_consumer")
	}
}

func (s *Session) close(code string) {
	s.closeOnce.Do(func() {
		s.closeCode = code
		close(s.done)
	})
}

// keyFor returns the key slot for an incoming key id (caller holds umu).
func (s *Session) keyFor(id byte, now time.Time) *keySlot {
	if s.cur.valid && s.cur.id == id && now.Before(s.cur.expiresAt) {
		return &s.cur
	}
	if s.prev.valid && s.prev.id == id && now.Before(s.prev.retireAt) {
		return &s.prev
	}
	return nil
}

// sendKey returns the key used for backend -> client datagrams (caller holds umu).
func (s *Session) sendKey(now time.Time) *keySlot {
	if s.prev.valid && !s.sendWithNew && now.Before(s.prev.retireAt) {
		return &s.prev
	}
	return &s.cur
}

// rotate installs a new current key (caller holds umu).
func (s *Session) rotate(ttl time.Duration, now time.Time) keySlot {
	s.prev = s.cur
	s.prev.retireAt = now.Add(keyGrace)
	s.cur = newKeySlot(s.prev.id+1, ttl, now)
	s.sendWithNew = false
	return s.cur
}
