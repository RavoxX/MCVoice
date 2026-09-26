# Protocol overview

The normative definition is
[`protocol/specification/mcvoice-protocol-v1.md`](../protocol/specification/mcvoice-protocol-v1.md).
Byte-exact vectors live in [`protocol/test-vectors/`](../protocol/test-vectors/),
and all three implementations (Java client, Go and Rust backends) check
themselves against them in CI. This page is a short tour.

## Two channels

| | Control | Voice |
|---|---|---|
| Transport | WebSocket `/v1/control`, TLS (`wss://`) in production | UDP, default port 24455 |
| Encoding | JSON text frames, ≤ 64 KiB | 22-byte header + AES-128-GCM ciphertext, ≤ 1200 bytes |
| Carries | versioning, auth, session keys, world scope, positions, visible peers, state, presence, key rotation, heartbeats | one 20 ms Opus frame per datagram, relayed by the backend |

## Session lifecycle

```
client                                   backend
  │── hello {versions, capabilities} ──────►│
  │◄──── hello_ok {challenge} ──────────────│
  │   (client: Mojang joinServer(challenge))│
  │── auth {method: mojang, username} ─────►│  hasJoined(username, challenge) → verified UUID
  │◄──── session {voice: host, port, connection_id, key_id, key; resume_token; config}
  │── UDP HELLO (encrypted) ──────────────►│  proves UDP path, binds source address
  │◄──── udp_ok (control) + HELLO_ACK (UDP) │
  │── scope {epoch, network_id, world_id} ─►│  new world session: clear pos + visibility
  │── pos {epoch, x, y, z}   (≤ 10 Hz) ────►│
  │── peers {epoch, full:[uuid…]} / peers_delta {add, remove}
  │══ UDP VOICE ═════════════════════════════►│  routing §8 → VOICE_RELAY to eligible recipients
  │◄═ UDP VOICE_RELAY {sender, recipient_epoch, frame}
  │◄──── key {key_id, key} (rotation, 30 s overlap)
  │── ping / pong, state {muted, deafened}, bye
```

* **Auth.** In `mojang` mode, the client proves account ownership exactly
  like joining an online-mode server: its access token goes only to
  Mojang's `joinServer`, and the backend checks `hasJoined`. Tokens never
  travel over our protocol. A `resume` token (HS256, short-lived) skips the
  round trip on reconnect.
* **Scope.** `world_id` is the dimension key. `network_id = "n1:" +
  sha256(host:port)[:32]` of the address the user joined is sent too, but is
  informational only: the same server has many addresses (aliases, IPs,
  tunnels, several proxies), so it never decides who hears whom. `epoch` increases on
  every world-session change (join, server switch, JoinGame on a proxy,
  dimension change, respawn, world replaced).
* **Peers.** The UUIDs of player entities the client currently tracks. The
  backend routes A → B only if B reported A **and** A reported B (mandatory).

* **Groups** (1.1, capability `groups`). `group_list` (with optional id
  search), `group_create` (optional password), `group_join`, `group_leave`;
  the backend answers `group_joined`/`group_update`/`group_left`. Voice mode
  `2` is group only, flag bit 1 adds the group to a normal/whisper frame.

## Voice datagram

```
 0      2   3    4     5       6               14              22
 ┌──────┬───┬────┬─────┬───────┬───────────────┬───────────────┬──────────────┐
 │ "MV" │ 1 │type│flags│key_id │ connection_id │   counter     │ ciphertext‖tag│
 └──────┴───┴────┴─────┴───────┴───────────────┴───────────────┴──────────────┘
         header (22 bytes) = AAD            nonce = dir ‖ key_id ‖ 00 00 ‖ counter
```

* AES-128-GCM with per-session keys from the control channel. The nonce is
  direction-scoped (`'C'`/`'S'`) and key-scoped, so client and server
  counters can never collide.
* Replay protection: a 1024-packet sliding window per (session, key).
* `VOICE` plaintext (15 bytes + Opus): sender epoch, sequence, 48 kHz
  timestamp, codec, mode (normal/whisper), flags (end of transmission) and
  payload length. `VOICE_RELAY` prepends the sender UUID and the
  **recipient's** epoch (35 bytes + Opus), so frames that cross a world
  switch are dropped by the receiver.

## Routing (backend) and playback (client)

Backend routing (§8) is a bandwidth filter:

* same `world_id` (and the same attested sub-server if both are attested);
* mutual visibility (each game tracks the other player), mandatory;
* distance ≤ range + 4 blocks slack;
* positions fresh within 3 s;
* not muted, banned or deafened.

The **client playback rule (§9)** is the security boundary for positional
audio. The speaker must currently be a tracked player entity in the
listener's world, within range by local positions, and not muted or
deafened. It is checked for every positional frame of every transport. Group
frames (mode 2, §9.1) are played centred instead, and only if the speaker is a
member of the listener's current group. See
[proximity-security.md](proximity-security.md).

## Versioning

`major` must match (`incompatible_protocol` otherwise). `minor` changes only
add things: unknown JSON fields are ignored, and unknown message types get a
non-fatal `unknown_message`. Optional behaviour is negotiated with capability
strings (`presence`, `key_rotation`, `peers_delta`, `scope_attestation`,
`svc_interop`).

## Test vectors

`protocol/test-vectors/generate.py` (Python + `cryptography`) is the
reference generator. CI runs it with `--check`, so the committed vectors
cannot drift. Files:

* `udp.json`: datagrams;
* `replay.json`: replay window;
* `control.json`: control messages;
* `routing.json`: backend routing decisions;
* `playback.json`: client validator decisions;
* `dedup.json`: transport selection.
