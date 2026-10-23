package server

import (
	"encoding/json"
	"fmt"
	"github.com/RavoxX/MCVoice/backend/go/internal/auth"
	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"strings"
	"testing"
	"time"
)

func TestFullTwentyMemberDirectoryPageFitsControlFrame(t *testing.T) {
	s := &Server{groups: map[string]*group{}}
	for i := 0; i < 20; i++ {
		id := "AAAA" + string(protocol.GroupIDAlphabet[i])
		g := &group{id: id, groupType: "isolated"}
		for j := 0; j < 20; j++ {
			g.members = append(g.members, &Session{ident: auth.Identity{UUID: fmt.Sprintf("%08x-0000-4000-8000-%012x", i, j), Username: strings.Repeat("x", 16)}})
		}
		s.groups[id] = g
	}
	viewer := &Session{groupsCap: true, out: make(chan []byte, 1), done: make(chan struct{})}
	limit := uint32(20)
	request := ^uint32(0)
	s.groupList(viewer, &protocol.GroupList{Details: true, Limit: &limit, RequestID: &request})
	select {
	case frame := <-viewer.out:
		if len(frame) > protocol.MaxControlFrame {
			t.Fatalf("directory page exceeds existing control bound: %d", len(frame))
		}
		var response struct {
			Groups []struct {
				Roster []memberJSON `json:"roster"`
			} `json:"groups"`
		}
		if err := json.Unmarshal(frame, &response); err != nil {
			t.Fatal(err)
		}
		if len(response.Groups) != 20 {
			t.Fatal("page was truncated")
		}
		for _, g := range response.Groups {
			if len(g.Roster) != 20 {
				t.Fatal("roster was truncated")
			}
		}
	case <-time.After(time.Second):
		t.Fatal("no directory response")
	}
}

func TestGroupBudgets(t *testing.T) {
	now := time.Now()
	limits := newGroupLimits()
	for _, tc := range []struct {
		msg    any
		burst  int
		refill time.Duration
	}{
		{&protocol.GroupList{}, 4, 500 * time.Millisecond},
		{&protocol.GroupCreate{}, 3, 10 * time.Second},
		{&protocol.GroupJoin{}, 6, time.Second},
	} {
		for i := 0; i < tc.burst; i++ {
			if limits.check(tc.msg, now) != "" {
				t.Fatal("burst rejected")
			}
		}
		if limits.check(tc.msg, now) == "" {
			t.Fatal("limit not enforced")
		}
		if limits.check(&protocol.GroupLeave{}, now) != "" {
			t.Fatal("leave must remain available")
		}
		if limits.check(tc.msg, now.Add(tc.refill)) != "" {
			t.Fatal("did not refill")
		}
		if limits.check(tc.msg, now.Add(tc.refill)) == "" {
			t.Fatal("refilled more than one token")
		}
	}
	other := newGroupLimits()
	if other.check(&protocol.GroupList{}, now) != "" {
		t.Fatal("budgets leaked across sessions")
	}
}
