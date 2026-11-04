package conformance

import (
	"bytes"
	"context"
	"time"

	"github.com/RavoxX/MCVoice/backend/go/pkg/voiceclient"
)

// Exercise real encrypted traffic, including a legacy recipient whose parser
// cannot accept the new flag, and a legacy sender trying to assert it anyway.
func scFECNegotiation(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(2, 64, 0))
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	c, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL,
		Username: randomName(), UUID: randomUUID(), Capabilities: []string{"opus", "groups"}})
	if err != nil {
		return err
	}
	e.clients = append(e.clients, c)
	if err = place(c, at(4, 64, 0)); err != nil {
		return err
	}
	players := []*voiceclient.Client{a, b, c}
	for _, p := range players {
		var visible []string
		for _, q := range players {
			if p != q {
				visible = append(visible, q.Session.PlayerUUID)
			}
		}
		if err = p.Peers(visible); err != nil {
			return err
		}
	}
	if err = syncAll(players...); err != nil {
		return err
	}
	check := func(recipient *voiceclient.Client, mode, flags byte) error {
		frame, err := expectFrom(recipient, a.Session.PlayerUUID, groupWait)
		if err != nil {
			return err
		}
		if frame.Mode != mode || frame.Flags != flags || !bytes.Equal(frame.Payload, opus) {
			return errorf("FEC relay changed payload/mode/flags: %+v", frame)
		}
		return nil
	}
	if err = a.SendVoice(1, 960, 0, 4, opus); err != nil {
		return err
	}
	if err = check(b, 0, 4); err != nil {
		return err
	}
	if err = check(c, 0, 0); err != nil {
		return err
	}
	if err = c.SendVoice(2, 1920, 0, 4, opus); err != nil {
		return err
	}
	for _, p := range []*voiceclient.Client{a, b} {
		if err = expectNothing(p, 150*time.Millisecond); err != nil {
			return errorf("unnegotiated FEC accepted: %v", err)
		}
	}
	if err = c.SendVoice(3, 2880, 0, 0, opus); err != nil {
		return err
	}
	for _, p := range []*voiceclient.Client{a, b} {
		frame, err := expectFrom(p, c.Session.PlayerUUID, groupWait)
		if err != nil {
			return err
		}
		if frame.Flags != 0 {
			return errorf("legacy sender gained FEC marker")
		}
	}
	id, err := createGroup(a, "")
	if err != nil {
		return err
	}
	for _, p := range []*voiceclient.Client{b, c} {
		if _, err = joinGroup(p, id, ""); err != nil {
			return err
		}
	}
	if err = syncAll(players...); err != nil {
		return err
	}
	if err = a.SendVoice(4, 3840, 0, 6, opus); err != nil {
		return err
	}
	if err = check(b, 2, 4); err != nil {
		return err
	}
	if err = check(c, 2, 0); err != nil {
		return err
	}
	for _, p := range []*voiceclient.Client{b, c} {
		if err = expectNothing(p, 100*time.Millisecond); err != nil {
			return errorf("duplicate group/proximity FEC: %v", err)
		}
	}
	return nil
}
