package conformance

import (
	"context"
	"encoding/json"
	"github.com/RavoxX/MCVoice/backend/go/pkg/voiceclient"
	"time"
)

func scGroupModeCapability(e *Env) error {
	owner, err := e.Player()
	if err != nil {
		return err
	}
	if err = inWorld(owner, at(0, 64, 0)); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	legacy, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: randomName(), UUID: randomUUID(), Capabilities: []string{"opus", "groups"}})
	if err != nil {
		return err
	}
	e.clients = append(e.clients, legacy)
	if err = inWorld(legacy, at(1, 64, 0)); err != nil {
		return err
	}
	if err = legacy.SendRaw([]byte(`{"type":"group_create","group_type":"normal"}`)); err != nil {
		return err
	}
	if err = expectError(legacy, "bad_message"); err != nil {
		return err
	}
	for _, policy := range []string{"normal", "isolated", "open"} {
		raw, _ := json.Marshal(map[string]any{"type": "group_create", "group_type": policy})
		if err = owner.SendRaw(raw); err != nil {
			return err
		}
		ev, err := owner.NextGroupEvent("group_joined", groupWait)
		if err != nil {
			return err
		}
		if err = legacy.GroupJoin(ev.Group.ID, ""); err != nil {
			return err
		}
		if policy == "open" {
			if _, err = legacy.NextGroupEvent("group_joined", groupWait); err != nil {
				return err
			}
		} else {
			if err = expectError(legacy, "bad_message"); err != nil {
				return errorf("legacy client joined %s group: %v", policy, err)
			}
		}
	}
	return nil
}

// Actual encrypted UDP delivery, including a plain positional packet that tries
// to bypass the backend-owned group policy by omitting group flags.
func scGroupModes(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	c, err := e.Player()
	if err != nil {
		return err
	}
	if err = inWorld(c, at(5, 64, 0)); err != nil {
		return err
	}
	if err = a.Peers([]string{b.Session.PlayerUUID, c.Session.PlayerUUID}); err != nil {
		return err
	}
	if err = b.Peers([]string{a.Session.PlayerUUID, c.Session.PlayerUUID}); err != nil {
		return err
	}
	if err = c.Peers([]string{a.Session.PlayerUUID, b.Session.PlayerUUID}); err != nil {
		return err
	}
	for _, policy := range []string{"normal", "open", "isolated"} {
		raw, _ := json.Marshal(map[string]any{"type": "group_create", "group_type": policy})
		if err = a.SendRaw(raw); err != nil {
			return err
		}
		ev, err := a.NextGroupEvent("group_joined", groupWait)
		if err != nil {
			return err
		}
		if ev.Group == nil || ev.Group.GroupType != policy || ev.Group.Max != 20 {
			return errorf("typed group metadata: %+v", ev.Group)
		}
		if _, err = joinGroup(b, ev.Group.ID, ""); err != nil {
			return err
		}
		for i, p := range []*voiceclient.Client{a, b, c} {
			if err = p.Pos(float64(i*3), 64, 0); err != nil {
				return err
			}
		}
		if err = syncAll(a, b, c); err != nil {
			return err
		}
		drain(a, b, c)
		if err = a.SendVoice(21, 960, 0, 2, opus); err != nil {
			return err
		}
		frame, err := expectFrom(b, a.Session.PlayerUUID, groupWait)
		if err != nil {
			return err
		}
		if frame.Mode != 2 {
			return errorf("%s: group delivery was positional", policy)
		}
		if err = expectNothing(b, 100*time.Millisecond); err != nil {
			return err
		}
		if policy == "open" {
			if _, err = expectFrom(c, a.Session.PlayerUUID, groupWait); err != nil {
				return err
			}
		} else {
			if err = expectNothing(c, 150*time.Millisecond); err != nil {
				return errorf("%s group leaked to outsider: %v", policy, err)
			}
		}
		if err = a.SendVoice(22, 1920, 0, 0, opus); err != nil {
			return err
		}
		if policy == "open" {
			if _, err = expectFrom(c, a.Session.PlayerUUID, groupWait); err != nil {
				return err
			}
			drain(b)
		} else {
			if err = expectNothing(c, 150*time.Millisecond); err != nil {
				return errorf("%s bypassed with plain flags: %v", policy, err)
			}
		}
		if err = c.SendVoice(23, 2880, 0, 0, opus); err != nil {
			return err
		}
		for _, p := range []*voiceclient.Client{a, b} {
			if policy == "isolated" {
				if err = expectNothing(p, 150*time.Millisecond); err != nil {
					return errorf("isolated group heard outsider: %v", err)
				}
			} else {
				if _, err = expectFrom(p, c.Session.PlayerUUID, groupWait); err != nil {
					return err
				}
			}
		}
	}
	return nil
}
