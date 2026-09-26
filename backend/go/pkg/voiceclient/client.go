// Package voiceclient is a headless MCVoice protocol client. It is used by the
// integration tests of both backends and by tools/load-test to simulate many
// Minecraft clients without launching Minecraft.
package voiceclient

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"github.com/RavoxX/MCVoice/backend/go/internal/protocol"
)

type Options struct {
	ControlURL string // ws://host:port/v1/control
	Username   string
	UUID       string
	Method     string // "offline" (tests) or "mojang"
	// UDPHost overrides the voice host announced by the backend (e.g. inside docker).
	UDPHost string
	// Capabilities to advertise; defaults to the full v1 set.
	Capabilities []string
	// Join is called with the challenge before "auth" in mojang mode; it must
	// perform the session-server join (tests use a mock session server).
	Join func(challenge string) error
	// ResumeToken, if set, is used instead of auth.
	ResumeToken string
}

// GroupMember is one entry of a group member list (spec 6.12).
type GroupMember struct {
	UUID string `json:"uuid"`
	Name string `json:"name"`
}

// GroupInfo is one entry of a group_list answer.
type GroupInfo struct {
	ID       string `json:"id"`
	Members  int    `json:"members"`
	Max      int    `json:"max"`
	Password bool   `json:"password"`
}

// GroupEvent is a received group_list, group_joined, group_update or group_left message.
type GroupEvent struct {
	Type    string        `json:"type"`
	ID      string        `json:"id"`
	Reason  string        `json:"reason"`
	Members []GroupMember `json:"members"`
	Groups  []GroupInfo   `json:"groups"`
	Group   *struct {
		ID       string        `json:"id"`
		Max      int           `json:"max"`
		Password bool          `json:"password"`
		Members  []GroupMember `json:"members"`
	} `json:"group"`
}

// RelayFrame is one received VOICE_RELAY datagram.
type RelayFrame struct {
	Sender         string
	RecipientEpoch uint32
	SenderEpoch    uint32
	Sequence       uint16
	Timestamp      uint32
	Mode           byte
	Flags          byte
	Payload        []byte
	Received       time.Time
}

type key struct {
	id     byte
	aead   *protocol.AEAD
	window protocol.ReplayWindow
}

// Client is one simulated player.
type Client struct {
	opts    Options
	ws      *websocket.Conn
	ctx     context.Context
	cancel  context.CancelFunc
	udp     *net.UDPConn
	connID  uint64
	uuidB   [16]byte
	Session SessionInfo

	mu        sync.Mutex
	cur, prev *key
	sendCtr   uint64
	epoch     uint32
	peersRev  uint32
	presence  map[string]bool
	udpOK     chan struct{}
	udpOnce   sync.Once
	resync    atomic.Int32
	lastPong  atomic.Int64
	RTT       atomic.Int64 // nanoseconds, from UDP ping

	pongs       chan uint64
	nonce       atomic.Uint64
	relays      chan RelayFrame
	groupEvents chan GroupEvent
	errors      chan string
	wmu         sync.Mutex
	closed      atomic.Bool
	Stats       Stats
	LastErr     atomic.Value
	sendBufs    []byte
}

type Stats struct {
	Sent, Received, Rejected atomic.Uint64
}

type SessionInfo struct {
	SessionID   string  `json:"session_id"`
	PlayerUUID  string  `json:"player_uuid"`
	Username    string  `json:"username"`
	ResumeToken string  `json:"resume_token"`
	Voice       voice   `json:"voice"`
	Config      sessCfg `json:"config"`
}

type voice struct {
	Host         string `json:"host"`
	Port         int    `json:"port"`
	ConnectionID string `json:"connection_id"`
	KeyID        int    `json:"key_id"`
	Key          string `json:"key"`
}

type sessCfg struct {
	NormalRange  float64 `json:"normal_range"`
	WhisperRange float64 `json:"whisper_range"`
}

// ServerHello is the parsed hello_ok.
type ServerHello struct {
	Server struct {
		Implementation string `json:"implementation"`
		Version        string `json:"version"`
	} `json:"server"`
	Auth struct {
		Methods   []string `json:"methods"`
		Challenge string   `json:"challenge"`
	} `json:"auth"`
}

var ErrRejected = errors.New("rejected by backend")

// Dial connects, authenticates and establishes the UDP voice path.
func Dial(ctx context.Context, o Options) (*Client, error) {
	if o.Method == "" {
		o.Method = "offline"
	}
	if o.Capabilities == nil {
		o.Capabilities = []string{"opus", "whisper", "peers_delta", "presence", "key_rotation", "groups"}
	}
	c := &Client{opts: o, presence: map[string]bool{}, udpOK: make(chan struct{}),
		relays: make(chan RelayFrame, 4096), errors: make(chan string, 64), pongs: make(chan uint64, 16),
		groupEvents: make(chan GroupEvent, 64)}
	c.ctx, c.cancel = context.WithCancel(context.Background())
	dctx, dcancel := context.WithTimeout(ctx, 15*time.Second)
	defer dcancel()
	ws, _, err := websocket.Dial(dctx, o.ControlURL, nil)
	if err != nil {
		return nil, fmt.Errorf("dial control: %w", err)
	}
	ws.SetReadLimit(1 << 20)
	c.ws = ws
	fail := func(err error) (*Client, error) { c.Close(); return nil, err }

	if err := c.sendJSON(map[string]any{"type": "hello", "protocol": map[string]int{"major": 1, "minor": 0},
		"client":       map[string]string{"name": "mcvoice-loadtest", "version": "0.1.0", "minecraft": "sim", "loader": "none"},
		"capabilities": o.Capabilities}); err != nil {
		return fail(err)
	}
	var hello ServerHello
	if err := c.expect(dctx, "hello_ok", &hello); err != nil {
		return fail(err)
	}
	if o.Join != nil && o.ResumeToken == "" {
		if err := o.Join(hello.Auth.Challenge); err != nil {
			return fail(fmt.Errorf("session join: %w", err))
		}
	}
	auth := map[string]any{"type": "auth", "method": o.Method, "username": o.Username}
	if o.UUID != "" {
		auth["uuid"] = o.UUID
	}
	if o.ResumeToken != "" {
		auth = map[string]any{"type": "resume", "resume_token": o.ResumeToken}
	}
	if err := c.sendJSON(auth); err != nil {
		return fail(err)
	}
	if err := c.expect(dctx, "session", &c.Session); err != nil {
		return fail(err)
	}
	c.connID, err = strconv.ParseUint(c.Session.Voice.ConnectionID, 16, 64)
	if err != nil {
		return fail(err)
	}
	c.uuidB, _ = protocol.UUIDBytes(c.Session.PlayerUUID)
	raw, err := base64.StdEncoding.DecodeString(c.Session.Voice.Key)
	if err != nil {
		return fail(err)
	}
	a, err := protocol.NewAEAD(raw)
	if err != nil {
		return fail(err)
	}
	c.cur = &key{id: byte(c.Session.Voice.KeyID), aead: a}

	host := c.Session.Voice.Host
	if o.UDPHost != "" {
		host = o.UDPHost
	}
	raddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(c.Session.Voice.Port)))
	if err != nil {
		return fail(err)
	}
	c.udp, err = net.DialUDP("udp", nil, raddr)
	if err != nil {
		return fail(err)
	}
	go c.readControl()
	go c.readUDP()
	for attempt := 0; ; attempt++ {
		c.sendHello()
		select {
		case <-c.udpOK:
			return c, nil
		case <-time.After(500 * time.Millisecond):
			if attempt > 10 {
				return fail(errors.New("udp_ok not received (UDP blocked?)"))
			}
		case <-dctx.Done():
			return fail(dctx.Err())
		}
	}
}

// Hello returns nothing useful after dial; kept for symmetry with tests.
func (c *Client) ConnectionID() uint64 { return c.connID }

func (c *Client) sendJSON(v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.SendRaw(b)
}

// SendRaw writes a raw control frame (used by negative tests).
func (c *Client) SendRaw(b []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()
	return c.ws.Write(ctx, websocket.MessageText, b)
}

func (c *Client) expect(ctx context.Context, typ string, into any) error {
	for {
		_, b, err := c.ws.Read(ctx)
		if err != nil {
			return fmt.Errorf("waiting for %s: %w", typ, err)
		}
		var head struct {
			Type    string `json:"type"`
			Code    string `json:"code"`
			Message string `json:"message"`
		}
		if err := json.Unmarshal(b, &head); err != nil {
			return err
		}
		if head.Type == "error" {
			return fmt.Errorf("%w: %s (%s)", ErrRejected, head.Code, head.Message)
		}
		if head.Type == typ {
			return json.Unmarshal(b, into)
		}
	}
}

func (c *Client) readControl() {
	for {
		_, b, err := c.ws.Read(c.ctx)
		if err != nil {
			c.LastErr.Store(err.Error())
			c.cancel()
			return
		}
		var m struct {
			Type   string   `json:"type"`
			Code   string   `json:"code"`
			Add    []string `json:"add"`
			Remove []string `json:"remove"`
			KeyID  int      `json:"key_id"`
			Nonce  uint64   `json:"nonce"`
			Key    string   `json:"key"`
		}
		if json.Unmarshal(b, &m) != nil {
			continue
		}
		if strings.HasPrefix(m.Type, "group_") {
			var ev GroupEvent
			if json.Unmarshal(b, &ev) == nil {
				select {
				case c.groupEvents <- ev:
				default:
				}
			}
			continue
		}
		switch m.Type {
		case "udp_ok":
			c.udpOnce.Do(func() { close(c.udpOK) })
		case "presence":
			c.mu.Lock()
			for _, u := range m.Add {
				c.presence[u] = true
			}
			for _, u := range m.Remove {
				delete(c.presence, u)
			}
			c.mu.Unlock()
		case "peers_resync":
			c.resync.Add(1)
		case "key":
			raw, err := base64.StdEncoding.DecodeString(m.Key)
			if err != nil {
				continue
			}
			a, err := protocol.NewAEAD(raw)
			if err != nil {
				continue
			}
			c.mu.Lock()
			c.prev, c.cur, c.sendCtr = c.cur, &key{id: byte(m.KeyID), aead: a}, 0
			c.mu.Unlock()
		case "pong":
			c.lastPong.Store(time.Now().UnixNano())
			select {
			case c.pongs <- m.Nonce:
			default:
			}
		case "error":
			select {
			case c.errors <- m.Code:
			default:
			}
		}
	}
}

func (c *Client) readUDP() {
	buf := make([]byte, 2048)
	for {
		n, err := c.udp.Read(buf)
		if err != nil {
			if c.closed.Load() {
				return
			}
			continue
		}
		dg := buf[:n]
		h, err := protocol.ParseHeader(dg, protocol.DirS2C)
		if err != nil {
			c.Stats.Rejected.Add(1)
			continue
		}
		c.mu.Lock()
		k := c.cur
		if k.id != h.KeyID && c.prev != nil && c.prev.id == h.KeyID {
			k = c.prev
		}
		if k.id != h.KeyID || !k.window.Check(h.Counter) {
			c.mu.Unlock()
			c.Stats.Rejected.Add(1)
			continue
		}
		pt, err := k.aead.Open(nil, protocol.DirS2C, h, dg)
		if err != nil || protocol.ParsePlaintext(h.Type, pt) != nil {
			c.mu.Unlock()
			c.Stats.Rejected.Add(1)
			continue
		}
		k.window.Update(h.Counter)
		c.mu.Unlock()
		switch h.Type {
		case protocol.TypeHelloAck:
		case protocol.TypePong:
			t := binary.BigEndian.Uint64(pt)
			c.RTT.Store(time.Now().UnixNano() - int64(t))
		case protocol.TypeVoiceRelay:
			r, _ := protocol.ParseRelay(pt)
			c.Stats.Received.Add(1)
			f := RelayFrame{Sender: protocol.UUIDString(r.Sender), RecipientEpoch: r.RecipientEpoch, SenderEpoch: r.Voice.Epoch,
				Sequence: r.Voice.Sequence, Timestamp: r.Voice.Timestamp, Mode: r.Voice.Mode, Flags: r.Voice.Flags,
				Payload: append([]byte(nil), r.Voice.Payload...), Received: time.Now()}
			select {
			case c.relays <- f:
			default:
			}
		}
	}
}

func (c *Client) seal(t byte, pt []byte) []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sendCtr++
	out := c.cur.aead.Seal(c.sendBufs[:0], protocol.DirC2S, protocol.Header{Type: t, KeyID: c.cur.id, ConnectionID: c.connID, Counter: c.sendCtr}, pt)
	c.sendBufs = out
	return out
}

func (c *Client) sendHello() {
	pt := make([]byte, 24)
	copy(pt, c.uuidB[:])
	binary.BigEndian.PutUint64(pt[16:], uint64(time.Now().UnixMilli()))
	_, _ = c.udp.Write(c.seal(protocol.TypeHello, pt))
}

// Ping sends a UDP ping; the RTT is stored in c.RTT when the pong arrives.
func (c *Client) Ping() {
	var pt [8]byte
	binary.BigEndian.PutUint64(pt[:], uint64(time.Now().UnixNano()))
	_, _ = c.udp.Write(c.seal(protocol.TypePing, pt[:]))
}

// Scope announces a new world session and returns its epoch.
func (c *Client) Scope(inWorld bool, networkID, worldID string) (uint32, error) {
	c.mu.Lock()
	c.epoch++
	e := c.epoch
	c.peersRev = 0
	c.presence = map[string]bool{}
	c.mu.Unlock()
	m := map[string]any{"type": "scope", "epoch": e, "in_world": inWorld}
	if inWorld {
		m["network_id"], m["world_id"] = networkID, worldID
	}
	return e, c.sendJSON(m)
}

// ScopeWithAttestation is Scope with a companion-plugin attestation token.
func (c *Client) ScopeWithAttestation(networkID, worldID, attestation string) (uint32, error) {
	c.mu.Lock()
	c.epoch++
	e := c.epoch
	c.peersRev = 0
	c.mu.Unlock()
	return e, c.sendJSON(map[string]any{"type": "scope", "epoch": e, "in_world": true,
		"network_id": networkID, "world_id": worldID, "attestation": attestation})
}

func (c *Client) Epoch() uint32 { c.mu.Lock(); defer c.mu.Unlock(); return c.epoch }

func (c *Client) Pos(x, y, z float64) error {
	return c.sendJSON(map[string]any{"type": "pos", "epoch": c.Epoch(), "x": x, "y": y, "z": z})
}

func (c *Client) Peers(uuids []string) error {
	c.mu.Lock()
	c.peersRev++
	rev := c.peersRev
	c.mu.Unlock()
	if uuids == nil {
		uuids = []string{}
	}
	return c.sendJSON(map[string]any{"type": "peers", "epoch": c.Epoch(), "rev": rev, "full": uuids})
}

func (c *Client) PeersDelta(add, remove []string) error {
	c.mu.Lock()
	base := c.peersRev
	c.peersRev++
	rev := c.peersRev
	c.mu.Unlock()
	if add == nil {
		add = []string{}
	}
	if remove == nil {
		remove = []string{}
	}
	return c.sendJSON(map[string]any{"type": "peers_delta", "epoch": c.Epoch(), "base": base, "rev": rev, "add": add, "remove": remove})
}

// PeersDeltaWithBase sends a delta with an explicit (possibly wrong) base.
func (c *Client) PeersDeltaWithBase(base, rev uint32, add []string) error {
	return c.sendJSON(map[string]any{"type": "peers_delta", "epoch": c.Epoch(), "base": base, "rev": rev, "add": add, "remove": []string{}})
}

func (c *Client) State(muted, deafened bool) error {
	return c.sendJSON(map[string]any{"type": "state", "muted": muted, "deafened": deafened})
}

func (c *Client) ControlPing() error { return c.sendJSON(map[string]any{"type": "ping", "nonce": 1}) }

// Sync sends a control ping and waits for its pong, which guarantees the
// backend has applied every control message sent before it.
func (c *Client) Sync(timeout time.Duration) error {
	n := c.nonce.Add(1) + 1000
	if err := c.sendJSON(map[string]any{"type": "ping", "nonce": n}); err != nil {
		return err
	}
	deadline := time.After(timeout)
	for {
		select {
		case got := <-c.pongs:
			if got == n {
				return nil
			}
		case <-deadline:
			return errors.New("sync: pong not received")
		case <-c.ctx.Done():
			return errors.New("sync: connection closed")
		}
	}
}

// GroupList requests the group list (spec 6.12).
func (c *Client) GroupList() error { return c.sendJSON(map[string]any{"type": "group_list"}) }

// GroupSearch requests the groups whose id contains query.
func (c *Client) GroupSearch(query string) error {
	return c.sendJSON(map[string]any{"type": "group_list", "query": query})
}

// GroupCreate creates a group; password "" means open.
func (c *Client) GroupCreate(password string) error {
	m := map[string]any{"type": "group_create"}
	if password != "" {
		m["password"] = password
	}
	return c.sendJSON(m)
}

// GroupJoin joins group id; password "" sends none.
func (c *Client) GroupJoin(id, password string) error {
	m := map[string]any{"type": "group_join", "id": id}
	if password != "" {
		m["password"] = password
	}
	return c.sendJSON(m)
}

func (c *Client) GroupLeave() error { return c.sendJSON(map[string]any{"type": "group_leave"}) }

// NextGroupEvent waits for the next group message of the given type ("" = any).
func (c *Client) NextGroupEvent(typ string, timeout time.Duration) (GroupEvent, error) {
	deadline := time.After(timeout)
	for {
		select {
		case ev := <-c.groupEvents:
			if typ == "" || ev.Type == typ {
				return ev, nil
			}
		case <-deadline:
			return GroupEvent{}, fmt.Errorf("no %s message within %v", typ, timeout)
		case <-c.ctx.Done():
			return GroupEvent{}, errors.New("connection closed")
		}
	}
}

// NextError waits for the next error code from the backend.
func (c *Client) NextError(timeout time.Duration) (string, error) {
	select {
	case e := <-c.errors:
		return e, nil
	case <-time.After(timeout):
		return "", fmt.Errorf("no error within %v", timeout)
	}
}

// SendVoice sends one VOICE frame for the current epoch.
func (c *Client) SendVoice(seq uint16, ts uint32, mode, flags byte, payload []byte) error {
	return c.SendVoiceEpoch(c.Epoch(), seq, ts, mode, flags, payload)
}

func (c *Client) SendVoiceEpoch(epoch uint32, seq uint16, ts uint32, mode, flags byte, payload []byte) error {
	pt := protocol.AppendVoice(nil, protocol.Voice{Epoch: epoch, Sequence: seq, Timestamp: ts, Codec: protocol.CodecOpus, Mode: mode, Flags: flags, Payload: payload})
	_, err := c.udp.Write(c.seal(protocol.TypeVoice, pt))
	if err == nil {
		c.Stats.Sent.Add(1)
	}
	return err
}

// LastDatagram returns a copy of the most recently sealed datagram (for replay tests).
func (c *Client) LastDatagram() []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]byte(nil), c.sendBufs...)
}

// WriteUDP sends raw bytes to the relay (negative tests).
func (c *Client) WriteUDP(b []byte) error { _, err := c.udp.Write(b); return err }

func (c *Client) Relays() <-chan RelayFrame { return c.relays }
func (c *Client) Errors() <-chan string     { return c.errors }
func (c *Client) Resyncs() int              { return int(c.resync.Load()) }
func (c *Client) Done() <-chan struct{}     { return c.ctx.Done() }

func (c *Client) Presence() map[string]bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make(map[string]bool, len(c.presence))
	for k, v := range c.presence {
		out[k] = v
	}
	return out
}

// Drain discards buffered relays.
func (c *Client) Drain() {
	for {
		select {
		case <-c.relays:
		default:
			return
		}
	}
}

func (c *Client) Close() {
	if c.closed.Swap(true) {
		return
	}
	if c.ws != nil {
		_ = c.sendJSON(map[string]any{"type": "bye"})
		_ = c.ws.Close(websocket.StatusNormalClosure, "bye")
	}
	c.cancel()
	if c.udp != nil {
		_ = c.udp.Close()
	}
}
