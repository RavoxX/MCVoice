// Package conformance is the backend-independent behaviour suite. The same
// scenarios run against the Go backend (in-process) and the Rust backend
// (launched binary) in CI, proving the two are interchangeable.
package conformance

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"

	"github.com/RavoxX/MCVoice/backend/go/pkg/voiceclient"
)

const (
	Overworld = "minecraft:overworld"
	Nether    = "minecraft:the_nether"
)

// Net1 is the network id both "players" of a scenario connect through
// (spec 6.3: n1: + sha256("play.example.org:25565")[:32]).
var Net1 = networkID("play.example.org", 25565)
var Net2 = networkID("other.example.org", 25565)

func networkID(host string, port int) string {
	h := sha256.Sum256([]byte(fmt.Sprintf("%s:%d", strings.ToLower(host), port)))
	return "n1:" + hex.EncodeToString(h[:])[:32]
}

// Scenario is one behaviour check. Mode is the AUTH_MODE the target runs with.
type Scenario struct {
	Name string
	Mode string
	Run  func(*Env) error
}

// Env is passed to each scenario.
type Env struct {
	T       *Target
	clients []*voiceclient.Client
}

func (e *Env) cleanup() {
	for _, c := range e.clients {
		c.Close()
	}
}

func randomUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
}

func randomName() string {
	b := make([]byte, 5)
	_, _ = rand.Read(b)
	return "p_" + hex.EncodeToString(b)
}

// Player connects an offline-auth client.
func (e *Env) Player() (*voiceclient.Client, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	c, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: randomName(), UUID: randomUUID()})
	if err != nil {
		return nil, err
	}
	e.clients = append(e.clients, c)
	return c, nil
}

type placement struct {
	network, world string
	x, y, z        float64
}

func at(x, y, z float64) placement { return placement{Net1, Overworld, x, y, z} }

// Place sets scope and position for c.
func place(c *voiceclient.Client, p placement) error {
	if _, err := c.Scope(true, p.network, p.world); err != nil {
		return err
	}
	return c.Pos(p.x, p.y, p.z)
}

func syncAll(cs ...*voiceclient.Client) error {
	for _, c := range cs {
		if err := c.Sync(3 * time.Second); err != nil {
			return err
		}
	}
	return nil
}

// pair creates two players at the given placements that see each other.
func (e *Env) pair(pa, pb placement) (*voiceclient.Client, *voiceclient.Client, error) {
	a, err := e.Player()
	if err != nil {
		return nil, nil, err
	}
	b, err := e.Player()
	if err != nil {
		return nil, nil, err
	}
	if err := place(a, pa); err != nil {
		return nil, nil, err
	}
	if err := place(b, pb); err != nil {
		return nil, nil, err
	}
	if err := a.Peers([]string{b.Session.PlayerUUID}); err != nil {
		return nil, nil, err
	}
	if err := b.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return nil, nil, err
	}
	return a, b, syncAll(a, b)
}

var opus = bytes.Repeat([]byte{0xAB, 0xCD, 0xEF}, 20)

func expectFrom(c *voiceclient.Client, sender string, within time.Duration) (voiceclient.RelayFrame, error) {
	deadline := time.After(within)
	for {
		select {
		case f := <-c.Relays():
			if f.Sender == sender {
				return f, nil
			}
		case <-deadline:
			return voiceclient.RelayFrame{}, fmt.Errorf("no relay from %s within %v", sender, within)
		}
	}
}

// drain discards relays already in flight (e.g. before checking that nothing more arrives).
func drain(cs ...*voiceclient.Client) {
	for _, c := range cs {
		for empty := false; !empty; {
			select {
			case <-c.Relays():
			case <-time.After(100 * time.Millisecond):
				empty = true
			}
		}
	}
}

func expectNothing(c *voiceclient.Client, within time.Duration) error {
	select {
	case f := <-c.Relays():
		return fmt.Errorf("unexpected relay from %s (seq %d)", f.Sender, f.Sequence)
	case <-time.After(within):
		return nil
	}
}

func talk(c *voiceclient.Client, n int, mode byte) error {
	for i := 0; i < n; i++ {
		if err := c.SendVoice(uint16(100+i), uint32(960*i), mode, 0, opus); err != nil {
			return err
		}
	}
	return nil
}

func errorf(format string, a ...any) error { return fmt.Errorf(format, a...) }

// All returns every scenario.
func All() []Scenario {
	return []Scenario{
		{"handshake_incompatible_major_rejected", "offline", scIncompatible},
		{"handshake_first_message_must_be_hello", "offline", scFirstMustBeHello},
		{"handshake_newer_minor_and_unknown_fields_accepted", "offline", scNewerMinor},
		{"distance_10_blocks_delivered_both_ways", "offline", scNearby},
		{"distance_60_blocks_not_delivered", "offline", scFar},
		{"whisper_mode_uses_whisper_range", "offline", scWhisper},
		{"different_dimension_same_coordinates_not_delivered", "offline", scDimension},
		{"dimension_switch_stops_voice_immediately", "offline", scDimensionSwitch},
		{"different_address_same_server_delivered_both_ways", "offline", scDifferentAddress},
		{"proxy_subserver_same_coordinates_not_visible_not_delivered", "offline", scSubserver},
		{"attested_subservers_are_isolated", "offline", scAttested},
		{"one_sided_visibility_not_delivered", "offline", scOneSided},
		{"stale_epoch_packet_dropped", "offline", scStaleEpoch},
		{"recipient_epoch_is_current", "offline", scRecipientEpoch},
		{"disconnect_removes_peer", "offline", scDisconnect},
		{"replayed_datagram_rejected", "offline", scReplay},
		{"malformed_datagrams_rejected_without_crash", "offline", scMalformed},
		{"unauthenticated_udp_gets_no_response", "offline", scNoAmplification},
		{"presence_lists_visible_cloud_peers", "offline", scPresence},
		{"peers_delta_and_resync", "offline", scPeersDelta},
		{"muted_sender_and_deafened_recipient", "offline", scMuteDeafen},
		{"key_rotation_keeps_voice_flowing", "offline", scKeyRotation},
		{"new_session_replaces_old", "offline", scReplace},
		{"voice_rate_limited", "offline", scRateLimit},
		{"control_bad_message_closes", "offline", scBadControl},
		{"health_ready_metrics", "offline", scMetrics},
		{"mojang_auth_success", "mojang", scMojangOK},
		{"mojang_auth_failure", "mojang", scMojangFail},
		{"offline_auth_refused_in_mojang_mode", "mojang", scOfflineRefused},
		{"resume_token_reconnect", "mojang", scResume},
	}
}

// Run executes the scenarios matching mode and returns name -> error (nil = pass).
func Run(t *Target, mode string, only func(string) bool) map[string]error {
	out := map[string]error{}
	for _, sc := range All() {
		if sc.Mode != mode || (only != nil && !only(sc.Name)) {
			continue
		}
		e := &Env{T: t}
		func() {
			defer func() {
				if r := recover(); r != nil {
					out[sc.Name] = fmt.Errorf("panic: %v", r)
				}
			}()
			out[sc.Name] = sc.Run(e)
		}()
		e.cleanup()
	}
	return out
}

// --- raw control helpers ---

func rawDial(t *Target) (*websocket.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, t.ControlURL, nil)
	return ws, err
}

func rawExchange(ws *websocket.Conn, msg string) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := ws.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
		return nil, err
	}
	_, b, err := ws.Read(ctx)
	if err != nil {
		return nil, err
	}
	var m map[string]any
	return m, json.Unmarshal(b, &m)
}

func expectClosed(ws *websocket.Conn) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for {
		if _, _, err := ws.Read(ctx); err != nil {
			if ctx.Err() != nil {
				return errorf("connection not closed by backend")
			}
			return nil
		}
	}
}

const helloV1 = `{"type":"hello","protocol":{"major":1,"minor":0},"client":{"name":"conformance","version":"0","minecraft":"sim","loader":"none"},"capabilities":["opus"]}`

func scIncompatible(e *Env) error {
	ws, err := rawDial(e.T)
	if err != nil {
		return err
	}
	defer ws.CloseNow()
	m, err := rawExchange(ws, `{"type":"hello","protocol":{"major":2,"minor":0},"client":{},"capabilities":[]}`)
	if err != nil {
		return err
	}
	if m["type"] != "error" || m["code"] != "incompatible_protocol" || m["fatal"] != true {
		return errorf("expected fatal incompatible_protocol, got %v", m)
	}
	return expectClosed(ws)
}

func scFirstMustBeHello(e *Env) error {
	ws, err := rawDial(e.T)
	if err != nil {
		return err
	}
	defer ws.CloseNow()
	m, err := rawExchange(ws, `{"type":"ping","nonce":1}`)
	if err != nil {
		return err
	}
	if m["type"] != "error" || m["code"] != "bad_message" {
		return errorf("expected bad_message, got %v", m)
	}
	return expectClosed(ws)
}

func scNewerMinor(e *Env) error {
	ws, err := rawDial(e.T)
	if err != nil {
		return err
	}
	defer ws.CloseNow()
	m, err := rawExchange(ws, `{"type":"hello","protocol":{"major":1,"minor":7},"client":{"name":"x","version":"9","minecraft":"26.3","loader":"fabric"},"capabilities":["opus","future_cap"],"extra":{"a":[1,2]}}`)
	if err != nil {
		return err
	}
	if m["type"] != "hello_ok" {
		return errorf("expected hello_ok, got %v", m)
	}
	auth, _ := m["auth"].(map[string]any)
	ch, _ := auth["challenge"].(string)
	if len(ch) != 32 {
		return errorf("challenge must be 32 hex chars, got %q", ch)
	}
	proto, _ := m["protocol"].(map[string]any)
	if proto["major"] != float64(1) {
		return errorf("bad protocol in hello_ok: %v", proto)
	}
	return nil
}

func scNearby(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(10, 64, 0))
	if err != nil {
		return err
	}
	if err := a.SendVoice(4242, 123456, 0, 0, opus); err != nil {
		return err
	}
	f, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	if err != nil {
		return err
	}
	if f.Sequence != 4242 || f.Timestamp != 123456 || f.Mode != 0 || !bytes.Equal(f.Payload, opus) {
		return errorf("relayed frame altered: %+v", f)
	}
	if f.SenderEpoch != a.Epoch() || f.RecipientEpoch != b.Epoch() {
		return errorf("epochs wrong: sender %d/%d recipient %d/%d", f.SenderEpoch, a.Epoch(), f.RecipientEpoch, b.Epoch())
	}
	if err := b.SendVoice(1, 960, 0, 1, nil); err != nil { // end-of-stream marker
		return err
	}
	g, err := expectFrom(a, b.Session.PlayerUUID, 2*time.Second)
	if err != nil {
		return err
	}
	if g.Flags != 1 || len(g.Payload) != 0 {
		return errorf("eos frame altered: %+v", g)
	}
	return expectNothing(a, 200*time.Millisecond) // no echo of own voice
}

func scFar(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(60, 64, 0))
	if err != nil {
		return err
	}
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	return expectNothing(b, 500*time.Millisecond)
}

func scWhisper(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(20, 64, 0))
	if err != nil {
		return err
	}
	if err := talk(a, 3, 1); err != nil { // whisper, 20 > 8+4
		return err
	}
	if err := expectNothing(b, 400*time.Millisecond); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil { // normal reaches 20 blocks
		return err
	}
	_, err = expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	return err
}

func scDimension(e *Env) error {
	a, b, err := e.pair(placement{Net1, Overworld, 100, 64, 100}, placement{Net1, Nether, 100, 64, 100})
	if err != nil {
		return err
	}
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	return expectNothing(b, 500*time.Millisecond)
}

func scDimensionSwitch(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(5, 64, 0))
	if err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	if _, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second); err != nil {
		return err
	}
	// B changes dimension: new scope clears B's position and visible set.
	if err := place(b, placement{Net1, Nether, 0, 64, 0}); err != nil {
		return err
	}
	if err := syncAll(b); err != nil {
		return err
	}
	b.Drain()
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	if err := expectNothing(b, 500*time.Millisecond); err != nil {
		return err
	}
	// Even if B wrongly re-reports A as visible in the Nether, scopes differ.
	if err := b.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(b); err != nil {
		return err
	}
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	return expectNothing(b, 500*time.Millisecond)
}

// The same server joined through two addresses (alias, IP, tunnel): the network ids differ,
// but both games track the other player, so voice flows (network_id is informational, spec 6.3).
func scDifferentAddress(e *Env) error {
	a, b, err := e.pair(placement{Net1, Overworld, 100, 64, 100}, placement{Net2, Overworld, 102, 64, 100})
	if err != nil {
		return err
	}
	for _, p := range [][2]*voiceclient.Client{{a, b}, {b, a}} {
		if err := talk(p[0], 1, 0); err != nil {
			return err
		}
		if _, err := expectFrom(p[1], p[0].Session.PlayerUUID, 2*time.Second); err != nil {
			return errorf("different addresses, mutually visible: %v", err)
		}
	}
	// Visibility stays the gate: once A's world no longer tracks B, nothing flows either way.
	if err := a.Peers([]string{}); err != nil {
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	drain(a, b)
	if err := talk(b, 5, 0); err != nil {
		return err
	}
	return expectNothing(a, 500*time.Millisecond)
}

// Same public proxy address, same dimension, identical coordinates, but the
// players are on different sub-servers: neither client tracks the other.
func scSubserver(e *Env) error {
	a, err := e.Player()
	if err != nil {
		return err
	}
	b, err := e.Player()
	if err != nil {
		return err
	}
	for _, c := range []*voiceclient.Client{a, b} {
		if err := place(c, at(100, 64, 100)); err != nil {
			return err
		}
	}
	c, err := e.Player() // a third player on B's sub-server, tracked by B
	if err != nil {
		return err
	}
	if err := place(c, at(101, 64, 100)); err != nil {
		return err
	}
	if err := a.Peers([]string{}); err != nil {
		return err
	}
	if err := b.Peers([]string{c.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := c.Peers([]string{b.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(a, b, c); err != nil {
		return err
	}
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	if err := expectNothing(b, 500*time.Millisecond); err != nil {
		return err
	}
	if err := talk(c, 1, 0); err != nil { // sanity: B does hear C
		return err
	}
	_, err = expectFrom(b, c.Session.PlayerUUID, 2*time.Second)
	return err
}

func attestation(key []byte, network, subserver, player string) string {
	payload, _ := json.Marshal(map[string]any{"v": 1, "network": network, "subserver": subserver, "player": player, "iat": time.Now().Unix()})
	m := hmac.New(sha256.New, key)
	m.Write(payload)
	enc := base64.RawURLEncoding
	return enc.EncodeToString(payload) + "." + enc.EncodeToString(m.Sum(nil))
}

func scAttested(e *Env) error {
	if len(e.T.AttestationKey) == 0 {
		return nil
	}
	a, err := e.Player()
	if err != nil {
		return err
	}
	b, err := e.Player()
	if err != nil {
		return err
	}
	// Mutually "visible" (a lying client) at identical coordinates, but attested on different sub-servers.
	if _, err := a.ScopeWithAttestation(Net1, Overworld, attestation(e.T.AttestationKey, "testnet", "survival-1", a.Session.PlayerUUID)); err != nil {
		return err
	}
	if _, err := b.ScopeWithAttestation(Net1, Overworld, attestation(e.T.AttestationKey, "testnet", "survival-2", b.Session.PlayerUUID)); err != nil {
		return err
	}
	for _, p := range [][2]*voiceclient.Client{{a, b}, {b, a}} {
		if err := p[0].Pos(100, 64, 100); err != nil {
			return err
		}
		if err := p[0].Peers([]string{p[1].Session.PlayerUUID}); err != nil {
			return err
		}
	}
	if err := syncAll(a, b); err != nil {
		return err
	}
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	if err := expectNothing(b, 500*time.Millisecond); err != nil {
		return errorf("different attested sub-servers: %v", err)
	}
	// Same sub-server -> delivered.
	if _, err := b.ScopeWithAttestation(Net1, Overworld, attestation(e.T.AttestationKey, "testnet", "survival-1", b.Session.PlayerUUID)); err != nil {
		return err
	}
	if err := b.Pos(100, 64, 100); err != nil {
		return err
	}
	if err := b.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(b); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	if _, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second); err != nil {
		return err
	}
	// Only one side attested: the attestation cannot be compared, mutual visibility decides.
	if _, err := b.Scope(true, Net2, Overworld); err != nil {
		return err
	}
	if err := b.Pos(100, 64, 100); err != nil {
		return err
	}
	if err := b.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(b); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	_, err = expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	return err
}

func scOneSided(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	if err := a.Peers([]string{}); err != nil { // A no longer tracks B
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	return expectNothing(b, 500*time.Millisecond)
}

func scStaleEpoch(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	old := a.Epoch()
	if err := place(a, at(0, 64, 0)); err != nil { // new epoch (e.g. respawn)
		return err
	}
	if err := a.Peers([]string{b.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	for i := 0; i < 5; i++ {
		if err := a.SendVoiceEpoch(old, uint16(i), 0, 0, 0, opus); err != nil {
			return err
		}
	}
	if err := expectNothing(b, 500*time.Millisecond); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	_, err = expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	return err
}

func scRecipientEpoch(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	for i := 0; i < 3; i++ { // B respawns a few times
		if err := place(b, at(3, 64, 0)); err != nil {
			return err
		}
	}
	if err := b.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(b); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	f, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second)
	if err != nil {
		return err
	}
	if f.RecipientEpoch != b.Epoch() {
		return errorf("recipient_epoch %d, want %d", f.RecipientEpoch, b.Epoch())
	}
	return nil
}

func scDisconnect(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	b.Close()
	time.Sleep(200 * time.Millisecond)
	if err := talk(a, 5, 0); err != nil {
		return err
	}
	c, err := e.Player()
	if err != nil {
		return errorf("backend unusable after disconnect: %v", err)
	}
	if err := place(c, at(1, 64, 0)); err != nil {
		return err
	}
	if err := c.Peers([]string{a.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := a.Peers([]string{c.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(a, c); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	_, err = expectFrom(c, a.Session.PlayerUUID, 2*time.Second)
	return err
}

func scReplay(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	if err := a.SendVoice(7, 0, 0, 0, opus); err != nil {
		return err
	}
	dg := a.LastDatagram()
	if _, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second); err != nil {
		return err
	}
	for i := 0; i < 5; i++ {
		if err := a.WriteUDP(dg); err != nil {
			return err
		}
	}
	return expectNothing(b, 500*time.Millisecond)
}

func scMalformed(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	if err := a.SendVoice(1, 0, 0, 0, opus); err != nil {
		return err
	}
	good := a.LastDatagram()
	if _, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second); err != nil {
		return err
	}
	junk := [][]byte{
		{}, {0x4d}, bytes.Repeat([]byte{0}, 40), bytes.Repeat([]byte{0xff}, 1500),
		append([]byte("XV"), good[2:]...),
		append(append([]byte{}, good[:4]...), append([]byte{1}, good[5:]...)...),
	}
	flipped := append([]byte{}, good...)
	flipped[len(flipped)-1] ^= 0xff
	junk = append(junk, flipped)
	for i := 0; i < 50; i++ {
		r := make([]byte, 22+16+i*10)
		_, _ = rand.Read(r)
		copy(r, good[:14]) // valid magic + connection id, garbage rest
		junk = append(junk, r)
	}
	for _, j := range junk {
		_ = a.WriteUDP(j)
	}
	if err := expectNothing(b, 300*time.Millisecond); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	if _, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second); err != nil {
		return errorf("backend stopped relaying after malformed input: %v", err)
	}
	return nil
}

func scNoAmplification(e *Env) error {
	a, err := e.Player()
	if err != nil {
		return err
	}
	a.Ping()
	host := strings.TrimPrefix(strings.TrimPrefix(e.T.ControlURL, "ws://"), "wss://")
	host, _, _ = strings.Cut(host, "/")
	h, _, _ := net.SplitHostPort(host)
	conn, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.ParseIP(h), Port: a.Session.Voice.Port})
	if err != nil {
		return err
	}
	defer conn.Close()
	// A syntactically valid HELLO with a real connection id but a wrong key.
	dg := append([]byte{'M', 'V', 1, 0x01, 0, 0}, make([]byte, 16)...)
	for i := 0; i < 8; i++ {
		dg[6+i] = byte(a.ConnectionID() >> (56 - 8*i))
	}
	dg[21] = 1
	dg = append(dg, make([]byte, 24+16)...)
	for i := 0; i < 5; i++ {
		_, _ = conn.Write(dg)
	}
	_ = conn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	buf := make([]byte, 2048)
	if n, err := conn.Read(buf); err == nil {
		return errorf("backend answered an unauthenticated datagram with %d bytes", n)
	}
	return nil
}

func scPresence(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	deadline := time.Now().Add(3 * time.Second)
	for !a.Presence()[b.Session.PlayerUUID] {
		if time.Now().After(deadline) {
			return errorf("A never received presence for B")
		}
		time.Sleep(50 * time.Millisecond)
	}
	// Presence must not reveal a cloud player A does not see.
	c, err := e.Player()
	if err != nil {
		return err
	}
	if err := place(c, at(1, 64, 1)); err != nil {
		return err
	}
	time.Sleep(1200 * time.Millisecond)
	if a.Presence()[c.Session.PlayerUUID] {
		return errorf("presence leaked a player A does not track")
	}
	// B leaves the world -> removed.
	if _, err := b.Scope(false, "", ""); err != nil {
		return err
	}
	deadline = time.Now().Add(3 * time.Second)
	for a.Presence()[b.Session.PlayerUUID] {
		if time.Now().After(deadline) {
			return errorf("presence for B not removed after B left the world")
		}
		time.Sleep(50 * time.Millisecond)
	}
	return nil
}

func scPeersDelta(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	if err := a.PeersDelta(nil, []string{b.Session.PlayerUUID}); err != nil {
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	if err := talk(a, 3, 0); err != nil {
		return err
	}
	if err := expectNothing(b, 400*time.Millisecond); err != nil {
		return errorf("after delta remove: %v", err)
	}
	if err := a.PeersDelta([]string{b.Session.PlayerUUID}, nil); err != nil {
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	if err := talk(a, 1, 0); err != nil {
		return err
	}
	if _, err := expectFrom(b, a.Session.PlayerUUID, 2*time.Second); err != nil {
		return errorf("after delta add: %v", err)
	}
	before := a.Resyncs()
	if err := a.PeersDeltaWithBase(999, 1000, []string{}); err != nil {
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	time.Sleep(100 * time.Millisecond)
	if a.Resyncs() != before+1 {
		return errorf("expected peers_resync for wrong base")
	}
	return nil
}

func scMuteDeafen(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	if err := a.State(true, false); err != nil {
		return err
	}
	if err := syncAll(a); err != nil {
		return err
	}
	if err := talk(a, 3, 0); err != nil {
		return err
	}
	if err := expectNothing(b, 400*time.Millisecond); err != nil {
		return errorf("muted: %v", err)
	}
	if err := a.State(false, false); err != nil {
		return err
	}
	if err := b.State(false, true); err != nil {
		return err
	}
	if err := syncAll(a, b); err != nil {
		return err
	}
	if err := talk(a, 3, 0); err != nil {
		return err
	}
	if err := expectNothing(b, 400*time.Millisecond); err != nil {
		return errorf("deafened: %v", err)
	}
	return nil
}

func scKeyRotation(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	stop := time.Now().Add(e.T.Rotation*2 + 2*time.Second)
	seq, got := 0, 0
	tick := time.NewTicker(20 * time.Millisecond)
	defer tick.Stop()
	for time.Now().Before(stop) {
		<-tick.C
		if seq%10 == 0 { // realistic 5 Hz position updates
			_ = a.Pos(0, 64, 0)
			_ = b.Pos(3, 64, 0)
		}
		if err := a.SendVoice(uint16(seq), uint32(seq*960), 0, 0, opus); err != nil {
			return err
		}
		seq++
	drain:
		for {
			select {
			case f := <-b.Relays():
				if f.Sender == a.Session.PlayerUUID {
					got++
				}
			default:
				break drain
			}
		}
	}
	time.Sleep(300 * time.Millisecond)
	for {
		select {
		case f := <-b.Relays():
			if f.Sender == a.Session.PlayerUUID {
				got++
			}
			continue
		default:
		}
		break
	}
	if seq-got > 3 {
		_, m, _ := httpGet(e.T.HTTPBase + "/metrics")
		var drops []string
		for _, l := range strings.Split(m, "\n") {
			if strings.HasPrefix(l, "mcvoice_packets_dropped_total{") || strings.HasPrefix(l, "mcvoice_invalid_packets_total{") {
				drops = append(drops, l)
			}
		}
		return errorf("lost %d of %d frames across key rotation (receiver rejected %d); %v", seq-got, seq, b.Stats.Rejected.Load(), drops)
	}
	return nil
}

func scReplace(e *Env) error {
	a, err := e.Player()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	a2, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: a.Session.Username, UUID: a.Session.PlayerUUID})
	if err != nil {
		return err
	}
	e.clients = append(e.clients, a2)
	select {
	case <-a.Done():
		return nil
	case <-time.After(3 * time.Second):
		return errorf("old session was not closed when replaced")
	}
}

func scRateLimit(e *Env) error {
	a, b, err := e.pair(at(0, 64, 0), at(3, 64, 0))
	if err != nil {
		return err
	}
	for i := 0; i < 600; i++ {
		_ = a.SendVoice(uint16(i), 0, 0, 0, opus)
	}
	time.Sleep(700 * time.Millisecond)
	n := 0
	for {
		select {
		case <-b.Relays():
			n++
			continue
		default:
		}
		break
	}
	if n == 0 || n > 250 {
		return errorf("rate limit: %d of 600 flood frames relayed (want 1..250)", n)
	}
	return nil
}

func scBadControl(e *Env) error {
	a, err := e.Player()
	if err != nil {
		return err
	}
	_ = a.SendRaw([]byte(`{"type":"pos","epoch":1,"x":"nope","y":0,"z":0}`))
	select {
	case <-a.Done():
		return nil
	case <-time.After(3 * time.Second):
		return errorf("malformed control message did not close the session")
	}
}

func httpGet(url string) (int, string, error) {
	resp, err := http.Get(url)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return resp.StatusCode, string(b), nil
}

func scMetrics(e *Env) error {
	for _, p := range []string{"/health", "/ready"} {
		code, _, err := httpGet(e.T.HTTPBase + p)
		if err != nil || code != 200 {
			return errorf("%s: %d %v", p, code, err)
		}
	}
	code, body, err := httpGet(e.T.HTTPBase + "/metrics")
	if err != nil || code != 200 {
		return errorf("/metrics: %d %v", code, err)
	}
	for _, name := range []string{"mcvoice_build_info", "mcvoice_connected_clients", "mcvoice_active_voice_sessions",
		"mcvoice_packets_received_total", "mcvoice_packets_sent_total", "mcvoice_bytes_received_total", "mcvoice_bytes_sent_total",
		"mcvoice_packets_dropped_total", "mcvoice_invalid_packets_total", "mcvoice_auth_failures_total",
		"mcvoice_relay_latency_seconds_bucket", "mcvoice_voice_jitter_seconds"} {
		if !strings.Contains(body, name) {
			return errorf("metric %s missing", name)
		}
	}
	return nil
}

func undash(u string) string { return strings.ReplaceAll(u, "-", "") }

func (e *Env) mojangPlayer(token string) (*voiceclient.Client, string, error) {
	uuid, name := randomUUID(), randomName()
	e.T.Mojang.AddAccount(token, undash(uuid), name)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	c, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: name, Method: "mojang",
		Join: func(ch string) error { return e.T.Mojang.Join(token, undash(uuid), ch) }})
	if err == nil {
		e.clients = append(e.clients, c)
	}
	return c, uuid, err
}

func scMojangOK(e *Env) error {
	c, uuid, err := e.mojangPlayer("token-" + randomName())
	if err != nil {
		return err
	}
	if c.Session.PlayerUUID != uuid {
		return errorf("session uuid %s, want %s", c.Session.PlayerUUID, uuid)
	}
	return nil
}

func scMojangFail(e *Env) error {
	name := randomName()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// Never joined: the backend's hasJoined check must fail.
	_, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: name, Method: "mojang",
		Join: func(string) error { return nil }})
	if err == nil || !strings.Contains(err.Error(), "auth_failed") {
		return errorf("expected auth_failed, got %v", err)
	}
	return nil
}

func scOfflineRefused(e *Env) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, Username: randomName(), UUID: randomUUID(), Method: "offline"})
	if err == nil || !strings.Contains(err.Error(), "unsupported_auth_method") {
		return errorf("expected unsupported_auth_method, got %v", err)
	}
	return nil
}

func scResume(e *Env) error {
	c, uuid, err := e.mojangPlayer("token-" + randomName())
	if err != nil {
		return err
	}
	tok := c.Session.ResumeToken
	c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c2, err := voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, ResumeToken: tok, Username: "x"})
	if err != nil {
		return errorf("resume failed: %v", err)
	}
	e.clients = append(e.clients, c2)
	if c2.Session.PlayerUUID != uuid || c2.Session.Voice.ConnectionID == c.Session.Voice.ConnectionID {
		return errorf("resume must keep identity and issue a new connection id")
	}
	_, err = voiceclient.Dial(ctx, voiceclient.Options{ControlURL: e.T.ControlURL, ResumeToken: tok[:len(tok)-2] + "xx", Username: "x"})
	if err == nil || !strings.Contains(err.Error(), "session_expired") {
		return errorf("tampered token: expected session_expired, got %v", err)
	}
	return nil
}
