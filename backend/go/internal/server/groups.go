package server

// Voice groups (spec 6.12): up to 15 sessions that hear each other non-positionally,
// across worlds and Minecraft servers. State is guarded by s.mu.

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"math/big"
	"sort"
	"strings"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
)

// outOfWorldGrace: out of a world for longer ends the membership (respawn, dimension or
// server switch are shorter).
const outOfWorldGrace = 10 * time.Second

const passwordFailsPerMin = 5

type group struct {
	id      string
	salt    []byte
	pwHash  []byte // SHA-256(salt || password); nil = open. Never logged, never sent.
	members []*Session
}

type outMsg struct {
	sess *Session
	msg  []byte
}

type memberJSON struct {
	UUID string `json:"uuid"`
	Name string `json:"name"`
}

func pwHash(salt []byte, pw string) []byte {
	h := sha256.New()
	h.Write(salt)
	h.Write([]byte(pw))
	return h.Sum(nil)
}

func (g *group) passwordOK(given *string) bool {
	if g.pwHash == nil {
		return true
	}
	if given == nil {
		return false
	}
	return subtle.ConstantTimeCompare(pwHash(g.salt, *given), g.pwHash) == 1
}

func (s *Server) newGroupIDLocked() string {
	n := big.NewInt(int64(len(protocol.GroupIDAlphabet)))
	for {
		b := make([]byte, protocol.GroupIDLen)
		for i := range b {
			k, err := rand.Int(rand.Reader, n)
			if err != nil {
				panic(err)
			}
			b[i] = protocol.GroupIDAlphabet[k.Int64()]
		}
		if _, taken := s.groups[string(b)]; !taken {
			return string(b)
		}
	}
}

func membersJSON(g *group) []memberJSON {
	out := make([]memberJSON, 0, len(g.members))
	for _, m := range g.members {
		out = append(out, memberJSON{UUID: m.ident.UUID, Name: m.ident.Username})
	}
	return out
}

func (s *Server) queueLocked(sess *Session, msg []byte) {
	s.outbox = append(s.outbox, outMsg{sess, msg})
}

func (s *Server) announceLocked(g *group, except *Session) {
	msg := marshal(map[string]any{"type": "group_update", "id": g.id, "members": membersJSON(g)})
	for _, m := range g.members {
		if m != except {
			s.queueLocked(m, msg)
		}
	}
}

// leaveGroupLocked removes sess from its group; reason ("" = none) is sent to the leaver.
func (s *Server) leaveGroupLocked(sess *Session, reason string) {
	id := sess.peer.Group
	if id == "" {
		return
	}
	sess.peer.Group = ""
	if reason != "" {
		s.queueLocked(sess, marshal(map[string]any{"type": "group_left", "id": id, "reason": reason}))
	}
	g := s.groups[id]
	if g == nil {
		return
	}
	for i, m := range g.members {
		if m == sess {
			g.members = append(g.members[:i], g.members[i+1:]...)
			break
		}
	}
	if len(g.members) == 0 {
		delete(s.groups, id)
	} else {
		s.announceLocked(g, nil)
	}
}

func (s *Server) joinGroupLocked(sess *Session, g *group) {
	sess.peer.Group = g.id
	found := false
	for _, m := range g.members {
		if m == sess {
			found = true
		}
	}
	if !found {
		g.members = append(g.members, sess)
	}
	s.queueLocked(sess, marshal(map[string]any{"type": "group_joined", "group": map[string]any{
		"id": g.id, "max": protocol.GroupMaxMembers, "password": g.pwHash != nil, "members": membersJSON(g)}}))
	s.announceLocked(g, sess)
}

// flushLocked releases s.mu and sends the queued group messages.
func (s *Server) unlockAndFlush() {
	out := s.outbox
	s.outbox = nil
	s.mu.Unlock()
	for _, o := range out {
		o.sess.send(o.msg)
	}
}

func (s *Server) groupsAllowed(sess *Session) bool {
	if !sess.groupsCap {
		sess.send(errorFrame(protocol.CodeUnknownMessage, "groups capability not negotiated", false))
	}
	return sess.groupsCap
}

func (s *Server) groupList(sess *Session, query *string) {
	if !s.groupsAllowed(sess) {
		return
	}
	type entry struct {
		ID       string `json:"id"`
		Members  int    `json:"members"`
		Max      int    `json:"max"`
		Password bool   `json:"password"`
	}
	s.mu.RLock()
	l := make([]entry, 0, len(s.groups))
	q := ""
	if query != nil {
		q = strings.ToUpper(*query)
	}
	for _, g := range s.groups {
		if q != "" && !strings.Contains(g.id, q) {
			continue
		}
		l = append(l, entry{g.id, len(g.members), protocol.GroupMaxMembers, g.pwHash != nil})
	}
	s.mu.RUnlock()
	sort.Slice(l, func(i, j int) bool {
		if l[i].Members != l[j].Members {
			return l[i].Members > l[j].Members
		}
		return l[i].ID < l[j].ID
	})
	if len(l) > protocol.GroupListMax {
		l = l[:protocol.GroupListMax]
	}
	sess.send(marshal(map[string]any{"type": "group_list", "groups": l}))
}

func (s *Server) groupCreate(sess *Session, m *protocol.GroupCreate) {
	if !s.groupsAllowed(sess) {
		return
	}
	s.mu.Lock()
	if !sess.peer.InWorld {
		s.mu.Unlock()
		sess.send(errorFrame(protocol.CodeNotInWorld, "join a server first", false))
		return
	}
	s.leaveGroupLocked(sess, "replaced")
	g := &group{id: s.newGroupIDLocked()}
	if m.Password != nil {
		g.salt = randomBytes(16)
		g.pwHash = pwHash(g.salt, *m.Password)
	}
	s.groups[g.id] = g
	s.joinGroupLocked(sess, g)
	s.unlockAndFlush()
}

func (s *Server) groupJoin(sess *Session, m *protocol.GroupJoin, now time.Time) {
	if !s.groupsAllowed(sess) {
		return
	}
	id := strings.ToUpper(*m.ID)
	s.mu.Lock()
	fail := func(code, msg string) {
		s.mu.Unlock()
		sess.send(errorFrame(code, msg, false))
	}
	if !sess.peer.InWorld {
		fail(protocol.CodeNotInWorld, "join a server first")
		return
	}
	if now.Sub(sess.groupFailWindow) > time.Minute {
		sess.groupFailWindow, sess.groupFails = now, 0
	}
	if sess.groupFails >= passwordFailsPerMin {
		fail(protocol.CodeRateLimited, "too many wrong group passwords")
		return
	}
	g := s.groups[id]
	already := sess.peer.Group == id
	switch {
	case g == nil:
		fail(protocol.CodeGroupNotFound, "no such group")
		return
	case already:
	case len(g.members) >= protocol.GroupMaxMembers:
		fail(protocol.CodeGroupFull, "group is full")
		return
	case !g.passwordOK(m.Password):
		sess.groupFails++
		fail(protocol.CodeGroupPassword, "wrong or missing password")
		return
	}
	if !already {
		s.leaveGroupLocked(sess, "replaced")
	}
	s.joinGroupLocked(sess, g)
	s.unlockAndFlush()
}

func (s *Server) groupLeave(sess *Session) {
	if !s.groupsAllowed(sess) {
		return
	}
	s.mu.Lock()
	s.leaveGroupLocked(sess, "left")
	s.unlockAndFlush()
}

// groupTick ends memberships of sessions out of a world longer than the grace period.
func (s *Server) groupTick(now time.Time) {
	s.mu.Lock()
	for _, sess := range s.byConn {
		if sess.peer.Group != "" && !sess.outOfWorldSince.IsZero() && now.Sub(sess.outOfWorldSince) > outOfWorldGrace {
			s.leaveGroupLocked(sess, "not_in_world")
		}
	}
	s.unlockAndFlush()
}
