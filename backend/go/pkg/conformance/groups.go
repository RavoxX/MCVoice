package conformance

// Voice group scenarios (spec 6.12, 8.1).

import (
	"context"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/pkg/voiceclient"
)

const groupWait = 3 * time.Second

// inWorld gives c a world scope (groups require one) and syncs.
func inWorld(c *voiceclient.Client, p placement) error {
	if err := place(c, p); err != nil {
		return err
	}
	return c.Sync(3 * time.Second)
}

func (e *Env) groupPlayers(n int) ([]*voiceclient.Client, error) {
	out := make([]*voiceclient.Client, 0, n)
	for i := 0; i < n; i++ {
		c, err := e.Player()
		if err != nil {
			return nil, err
		}
		if err := inWorld(c, at(float64(i*1000), 64, 0)); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, nil
}

// createGroup lets c create a group and returns its id.
func createGroup(c *voiceclient.Client, password string) (string, error) {
	if err := c.GroupCreate(password); err != nil {
		return "", err
	}
	ev, err := c.NextGroupEvent("group_joined", groupWait)
	if err != nil {
		return "", err
	}
	if ev.Group == nil || len(ev.Group.ID) != 5 || len(ev.Group.Members) != 1 || ev.Group.Max != 15 ||
		ev.Group.Password != (password != "") {
		return "", errorf("bad group_joined after create: %+v", ev.Group)
	}
	return ev.Group.ID, nil
}

func joinGroup(c *voiceclient.Client, id, password string) (voiceclient.GroupEvent, error) {
	if err := c.GroupJoin(id, password); err != nil {
		return voiceclient.GroupEvent{}, err
	}
	return c.NextGroupEvent("group_joined", groupWait)
}

func expectError(c *voiceclient.Client, code string) error {
	got, err := c.NextError(groupWait)
	if err != nil {
		return errorf("expected error %s: %v", code, err)
	}
	if got != code {
		return errorf("expected error %s, got %s", code, got)
	}
	return nil
}

func scGroupLifecycle(e *Env) error {
	ps, err := e.groupPlayers(2)
	if err != nil {
		return err
	}
	a, b := ps[0], ps[1]
	id, err := createGroup(a, "")
	if err != nil {
		return err
	}
	if err := b.GroupList(); err != nil {
		return err
	}
	l, err := b.NextGroupEvent("group_list", groupWait)
	if err != nil {
		return err
	}
	found := false
	for _, g := range l.Groups {
		if g.ID == id && g.Members == 1 && g.Max == 15 && !g.Password {
			found = true
		}
	}
	if !found {
		return errorf("group %s missing from list %+v", id, l.Groups)
	}
	// search: part of the id (any case) finds it; "11111" cannot match ('1' is not in the id alphabet)
	if err := b.GroupSearch(toLower(id[1:4])); err != nil {
		return err
	}
	if l, err = b.NextGroupEvent("group_list", groupWait); err != nil {
		return err
	}
	if len(l.Groups) == 0 || l.Groups[0].ID != id && !containsGroup(l.Groups, id) {
		return errorf("search for %s did not find %s: %+v", id[1:4], id, l.Groups)
	}
	if err := b.GroupSearch("11111"); err != nil {
		return err
	}
	if l, err = b.NextGroupEvent("group_list", groupWait); err != nil {
		return err
	}
	if len(l.Groups) != 0 {
		return errorf("search for 11111 returned %+v", l.Groups)
	}
	// ids are matched case-insensitively
	j, err := joinGroup(b, toLower(id), "")
	if err != nil {
		return err
	}
	if j.Group.ID != id || len(j.Group.Members) != 2 {
		return errorf("bad group_joined: %+v", j.Group)
	}
	u, err := a.NextGroupEvent("group_update", groupWait)
	if err != nil {
		return err
	}
	if len(u.Members) != 2 {
		return errorf("creator saw %d members, want 2", len(u.Members))
	}
	if err := b.GroupLeave(); err != nil {
		return err
	}
	left, err := b.NextGroupEvent("group_left", groupWait)
	if err != nil {
		return err
	}
	if left.Reason != "left" || left.ID != id {
		return errorf("bad group_left: %+v", left)
	}
	u, err = a.NextGroupEvent("group_update", groupWait)
	if err != nil {
		return err
	}
	if len(u.Members) != 1 {
		return errorf("creator saw %d members after leave, want 1", len(u.Members))
	}
	// the last member leaving deletes the group
	if err := a.GroupLeave(); err != nil {
		return err
	}
	if _, err := a.NextGroupEvent("group_left", groupWait); err != nil {
		return err
	}
	if err := b.GroupJoin(id, ""); err != nil {
		return err
	}
	return expectError(b, "group_not_found")
}

func containsGroup(l []voiceclient.GroupInfo, id string) bool {
	for _, g := range l {
		if g.ID == id {
			return true
		}
	}
	return false
}

func toLower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + 32
		}
	}
	return string(b)
}

func scGroupPassword(e *Env) error {
	ps, err := e.groupPlayers(2)
	if err != nil {
		return err
	}
	a, b := ps[0], ps[1]
	id, err := createGroup(a, "s3cret pw")
	if err != nil {
		return err
	}
	if err := b.GroupJoin(id, ""); err != nil {
		return err
	}
	if err := expectError(b, "group_password"); err != nil {
		return err
	}
	if _, err := joinGroup(b, id, "s3cret pw"); err != nil {
		return errorf("correct password: %v", err)
	}
	// wrong passwords are rate limited (5 per minute)
	c, err := e.groupPlayers(1)
	if err != nil {
		return err
	}
	for i := 0; i < 5; i++ {
		if err := c[0].GroupJoin(id, "wrong"); err != nil {
			return err
		}
		if err := expectError(c[0], "group_password"); err != nil {
			return err
		}
	}
	if err := c[0].GroupJoin(id, "s3cret pw"); err != nil {
		return err
	}
	return expectError(c[0], "rate_limited")
}

func scGroupFull(e *Env) error {
	ps, err := e.groupPlayers(16)
	if err != nil {
		return err
	}
	id, err := createGroup(ps[0], "")
	if err != nil {
		return err
	}
	for i := 1; i < 15; i++ {
		if _, err := joinGroup(ps[i], id, ""); err != nil {
			return errorf("member %d: %v", i+1, err)
		}
	}
	if err := ps[15].GroupJoin(id, ""); err != nil {
		return err
	}
	return expectError(ps[15], "group_full")
}

func scGroupNeedsWorld(e *Env) error {
	c, err := e.Player()
	if err != nil {
		return err
	}
	if _, err := c.Scope(false, "", ""); err != nil {
		return err
	}
	if err := c.GroupCreate(""); err != nil {
		return err
	}
	return expectError(c, "not_in_world")
}

func scGroupNeedsCapability(e *Env) error {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	c, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: randomName(), UUID: randomUUID(),
		Capabilities: []string{"opus", "whisper", "peers_delta", "presence", "key_rotation"}})
	if err != nil {
		return err
	}
	e.clients = append(e.clients, c)
	if err := c.GroupList(); err != nil {
		return err
	}
	return expectError(c, "unknown_message")
}

// Group voice ignores worlds, servers, positions and visibility; non-members get nothing.
func scGroupVoiceAcrossServers(e *Env) error {
	a, err := e.Player()
	if err != nil {
		return err
	}
	b, err := e.Player()
	if err != nil {
		return err
	}
	outsider, err := e.Player()
	if err != nil {
		return err
	}
	if err := inWorld(a, placement{Net1, Overworld, 0, 64, 0}); err != nil {
		return err
	}
	if err := inWorld(b, placement{Net2, "minecraft:the_nether", 9000, 64, 9000}); err != nil {
		return err
	}
	if err := inWorld(outsider, placement{Net1, Overworld, 1, 64, 0}); err != nil {
		return err
	}
	// the outsider stands next to A and both see each other: only a positional frame could reach it
	if err := a.Peers([]string{outsider.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := outsider.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	id, err := createGroup(a, "")
	if err != nil {
		return err
	}
	if _, err := joinGroup(b, id, ""); err != nil {
		return err
	}
	if err := syncAll(a, b, outsider); err != nil {
		return err
	}
	if err := a.SendVoice(7, 960, 2, 0, opus); err != nil {
		return err
	}
	f, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	if err != nil {
		return errorf("group member on another server: %v", err)
	}
	if f.Mode != 2 || f.Sequence != 7 {
		return errorf("group relay has mode %d seq %d, want mode 2 seq 7", f.Mode, f.Sequence)
	}
	if err := expectNothing(outsider, 400*time.Millisecond); err != nil {
		return errorf("group-only frame reached a non-member: %v", err)
	}
	// B replies; A hears it through the group
	if err := b.SendVoice(8, 960, 2, 0, opus); err != nil {
		return err
	}
	_, err = expectFrom(a, b.Session.PlayerUUID, 2*time.Second)
	return err
}

// A frame for group and proximity reaches a nearby group member once (as group audio) and
// a nearby non-member positionally.
func scGroupNoDoubleDelivery(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	c, err := e.Player()
	if err != nil {
		return err
	}
	if err := inWorld(c, at(5, 64, 0)); err != nil {
		return err
	}
	if err := a.Peers([]string{b.Session.PlayerUUID, c.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := c.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	id, err := createGroup(a, "")
	if err != nil {
		return err
	}
	if _, err := joinGroup(b, id, ""); err != nil {
		return err
	}
	if err := syncAll(a, b, c); err != nil {
		return err
	}
	drain(a, b, c)
	if err := a.SendVoice(11, 960, 0, 0x02, opus); err != nil {
		return err
	}
	fb, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	if err != nil {
		return err
	}
	if fb.Mode != 2 || fb.Flags != 0 {
		return errorf("group member got mode %d flags %d, want mode 2 flags 0", fb.Mode, fb.Flags)
	}
	if err := expectNothing(b, 400*time.Millisecond); err != nil {
		return errorf("group member heard the frame twice: %v", err)
	}
	fc, err := expectFrom(c, a.Session.PlayerUUID, 2*time.Second)
	if err != nil {
		return errorf("nearby non-member: %v", err)
	}
	if fc.Mode != 0 || fc.Flags != 0x02 {
		return errorf("nearby non-member got mode %d flags %d, want the frame unchanged (0, 2)", fc.Mode, fc.Flags)
	}
	// a group-only frame from someone in no group goes nowhere
	outsider, err := e.Player()
	if err != nil {
		return err
	}
	if err := inWorld(outsider, at(1, 64, 0)); err != nil {
		return err
	}
	if err := outsider.SendVoice(1, 960, 2, 0, opus); err != nil {
		return err
	}
	return expectNothing(b, 400*time.Millisecond)
}

// Disconnecting ends the membership at once; being out of a world ends it after the grace.
func scGroupMembershipEnds(e *Env) error {
	ps, err := e.groupPlayers(3)
	if err != nil {
		return err
	}
	a, b, c := ps[0], ps[1], ps[2]
	id, err := createGroup(a, "")
	if err != nil {
		return err
	}
	for _, p := range []*voiceclient.Client{b, c} {
		if _, err := joinGroup(p, id, ""); err != nil {
			return err
		}
	}
	for {
		u, err := a.NextGroupEvent("group_update", groupWait)
		if err != nil {
			return err
		}
		if len(u.Members) == 3 {
			break
		}
	}
	b.Close()
	u, err := a.NextGroupEvent("group_update", groupWait)
	if err != nil {
		return errorf("no update after a member disconnected: %v", err)
	}
	if len(u.Members) != 2 {
		return errorf("%d members after disconnect, want 2", len(u.Members))
	}
	// C leaves the world (menu, loading screen): still a member during the 10 s grace, then out
	if _, err := c.Scope(false, "", ""); err != nil {
		return err
	}
	if _, err := c.NextGroupEvent("group_left", 3*time.Second); err == nil {
		return errorf("membership ended before the grace period")
	}
	left, err := c.NextGroupEvent("group_left", 12*time.Second)
	if err != nil {
		return err
	}
	if left.Reason != "not_in_world" {
		return errorf("group_left reason %q, want not_in_world", left.Reason)
	}
	return nil
}
