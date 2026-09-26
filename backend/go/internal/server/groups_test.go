package server

import (
	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
	"testing"
	"time"
)

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
