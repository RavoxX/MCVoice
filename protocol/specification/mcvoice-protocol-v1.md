# MCVoice Protocol, version 1.0

Status: **normative** for `OUR_VOICE_PROTOCOL_MAJOR = 1`, `OUR_VOICE_PROTOCOL_MINOR = 0`.

Key words MUST, MUST NOT, SHOULD, MAY are used as in RFC 2119.

This document is the single source of truth for the wire format shared by

* the Java client (`client/`),
* the Rust backend (`backend/rust/`),
* the Go backend (`backend/go/`).

Byte-exact examples live in [`protocol/test-vectors/`](../test-vectors/); every
implementation runs them in CI. If this document and the vectors ever disagree,
that is a bug that has to be fixed before release.

---

## 1. Overview

```
 Minecraft client A                     MCVoice backend                    Minecraft client B
 ------------------                     ---------------                    ------------------
   control (WSS, JSON)  <------------>  control service  <------------>  control (WSS, JSON)
     hello / auth / scope / pos / peers        |  session state (memory)
                                               |
   voice (UDP, AES-128-GCM) ----------->  voice relay  ------------------>  voice (UDP)
```

* **Control channel** – one WebSocket per client, TLS (`wss://`) in production.
  JSON text frames. Carries version negotiation, authentication, session keys,
  world scope, positions, locally-tracked peers, heartbeats and presence.
* **Voice channel** – UDP datagrams, one AEAD-protected Opus frame each,
  nominally every 20 ms while the user is transmitting.
* Voice is **always relayed** by the backend. Clients never learn each other's
  IP addresses and never send media peer-to-peer.
* The Minecraft server is **not** involved. It is not a relay and needs no
  plugin. The client derives proximity from the entities the Minecraft server
  already sends it.

## 2. Versioning and negotiation

* `major` changes are incompatible. A peer receiving a different `major` MUST
  reject the connection with error `incompatible_protocol` (control) or drop
  the datagram (UDP header byte 2).
* `minor` changes are backwards compatible additions (new optional fields,
  new message types that can be ignored, new capability strings). Unknown JSON
  fields MUST be ignored. Unknown control message types MUST be answered with
  a non-fatal `error{code:"unknown_message"}` and otherwise ignored.
* Each side advertises `capabilities` (array of strings). Behaviour behind a
  capability is only used if both sides list it. Capabilities defined in 1.0:

| capability          | meaning |
|---------------------|---------|
| `opus`              | Opus 48 kHz mono voice frames (mandatory in 1.x) |
| `whisper`           | whisper voice mode |
| `peers_delta`       | incremental visible-peer updates (§6.5) |
| `presence`          | backend reports which visible peers are MCVoice cloud peers (§6.7) |
| `key_rotation`      | backend rotates voice keys during the session (§7.6) |
| `scope_attestation` | backend verifies optional companion-plugin scope attestations (§6.3.1) |
| `svc_interop`       | client-only; informative, lets the backend count hybrid clients |

## 3. Identifiers

| name | type | notes |
|------|------|-------|
| player UUID | RFC 4122 string, lowercase, hyphenated | Minecraft profile UUID. Verified by the backend in `mojang` auth mode. |
| `session_id` | opaque string ≤ 64 chars | control session handle; never used on UDP |
| `connection_id` | u64, hex string in JSON, big-endian on UDP | random per session, non-secret, selects the voice session on UDP |
| `key_id` | u8 | identifies the current voice key; increments (mod 256) on rotation |
| `epoch` | u32 | **world-session epoch**, chosen by the client, strictly increasing within a control session (§6.3) |
| `network_id` | string ≤ 64 chars `[0-9a-z:._-]` | client-computed hash of the server address the user joined (§6.3); informational, never used for routing |
| `world_id` | string ≤ 128 chars | dimension key as the client sees it, e.g. `minecraft:overworld` |

## 4. Transport limits

| limit | value |
|-------|-------|
| max control frame | 65 536 bytes |
| max UDP datagram accepted | 1 200 bytes |
| max Opus payload | 1 000 bytes |
| max `full`/`add`/`remove` list length | 512 UUIDs |
| max control messages per second per session | `RATE_LIMIT_CONTROL_PER_SEC` (default 40, burst 80) |
| max voice datagrams per second per session | `RATE_LIMIT_VOICE_PER_SEC` (default 75, burst 150) |
| heartbeat interval | 5 s (client `ping`) |
| session timeout | 20 s without any control message |
| position staleness | 3 000 ms |

Anything exceeding a limit MUST be rejected without allocation proportional to
the attacker-controlled size.

## 5. Control channel framing

* Endpoint: `GET /v1/control` upgraded to WebSocket. Production deployments
  MUST terminate TLS (either the backend with `TLS_CERT_PATH`/`TLS_KEY_PATH`
  or a reverse proxy). Clients MUST refuse `ws://` for non-loopback hosts
  unless the user explicitly enables insecure mode in the config.
* Every frame is one UTF-8 JSON object with a string member `type`.
* Binary frames are a protocol error (`bad_message`, fatal).
* Numbers are IEEE-754 doubles; integer fields MUST be integral and in range.

### 5.1 Error message

```json
{"type":"error","code":"auth_failed","message":"human readable","fatal":true}
```

| code | fatal | meaning |
|------|-------|---------|
| `incompatible_protocol` | yes | major version mismatch |
| `bad_message` | yes/no | malformed JSON / missing field / wrong type / too large |
| `unknown_message` | no | unknown `type`, ignored |
| `auth_required` | yes | message requires an authenticated session |
| `auth_failed` | yes | identity could not be verified |
| `unsupported_auth_method` | yes | method not enabled on this backend |
| `banned` | yes | player is banned from this backend |
| `rate_limited` | no (yes when sustained) | too many messages |
| `session_expired` | yes | resume token or session no longer valid |
| `stale_epoch` | no | message referenced an epoch older than the current one |
| `server_full` | yes | `MAX_SESSIONS` reached |
| `session_replaced` | yes | the same player authenticated on a newer connection; the older one is closed |
| `internal` | yes | unexpected backend failure |

After a fatal error the backend closes the WebSocket (close code 1008 policy,
or 1011 for `internal`).

## 6. Control messages

### 6.1 Hello

Client → backend, MUST be the first message, within 10 s of connecting.

```json
{"type":"hello",
 "protocol":{"major":1,"minor":0},
 "client":{"name":"mcvoice","version":"0.1.0","minecraft":"1.20.1","loader":"fabric"},
 "capabilities":["opus","whisper","peers_delta","presence","key_rotation"]}
```

Backend → client:

```json
{"type":"hello_ok",
 "protocol":{"major":1,"minor":0},
 "server":{"name":"mcvoice-backend","version":"0.1.0","implementation":"rust"},
 "capabilities":["opus","whisper","peers_delta","presence","key_rotation"],
 "auth":{"methods":["mojang"],"challenge":"3f7c0e8d4a1b2c3d4e5f60718293a4b5"}}
```

`challenge` is 32 lowercase hex characters from a CSPRNG, single-use, valid for 60 s.

### 6.2 Authentication

**`mojang` (production).** The client proves ownership of its Minecraft
account *without sending any Microsoft/Minecraft token to the backend*:

1. Client calls Mojang's session server `join` endpoint (exactly as when joining
   an online-mode Minecraft server) with `serverId = challenge`.
   The access token only ever goes to Mojang.
2. Client sends `{"type":"auth","method":"mojang","username":"Steve"}`.
3. Backend calls
   `GET https://sessionserver.mojang.com/session/minecraft/hasJoined?username=<username>&serverId=<challenge>`
   and takes the UUID from the response. The claimed username must match.

**`offline` (development only).** Enabled only with `AUTH_MODE=offline`.
`{"type":"auth","method":"offline","username":"Steve","uuid":"…"}` is trusted.
Backends MUST log a warning at start-up when offline auth is enabled.

**Resume.** `{"type":"resume","resume_token":"…"}` instead of `auth`, to
survive short disconnects without another Mojang round trip. The token is an
HS256 JWT signed with `JWT_SIGNING_SECRET`, claims
`{"sub":<uuid>,"name":<username>,"iat":…,"exp":…,"typ":"resume"}`, lifetime
`RESUME_TOKEN_TTL_SECONDS` (default 3600). A resume always creates a *new*
voice key and connection id.

On success the backend replies:

```json
{"type":"session",
 "session_id":"s_8f3a…",
 "player_uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5",
 "username":"Notch",
 "resume_token":"eyJ…",
 "voice":{"host":"voice.example.org","port":24455,
          "connection_id":"1a2b3c4d5e6f7081",
          "key_id":0,"key":"AAECAwQFBgcICQoLDA0ODw==","key_expires_in":600},
 "config":{"normal_range":48.0,"whisper_range":8.0,"max_range":96.0,
           "codec":"opus","sample_rate":48000,"frame_ms":20,
           "heartbeat_interval":5,"position_hz_max":10}}
```

`key` is 16 random bytes, base64 (RFC 4648, with padding). It MUST only be sent
over the control channel. It is never logged.

### 6.3 Scope (world session)

The client tells the backend which Minecraft world it is in. **Every** change
of connection, proxy sub-server, dimension, respawn, world unload or entity
tracker reset MUST produce a new scope with a larger `epoch`.

```json
{"type":"scope","epoch":7,"in_world":true,
 "network_id":"n1:6b86b273ff34fce19d6b804eff5a3f57",
 "world_id":"minecraft:overworld"}
```

* `network_id`: `"n1:"` + first 32 hex chars of
  `SHA-256(lowercase(host) + ":" + port)` of the address the user connected to
  (before SRV resolution). It is **informational only**: backends MUST NOT
  use it to decide who can hear whom (§8). The same server is reachable under
  many addresses (aliases, IPs, SRV records, tunnels, several proxies of one
  network, LAN), and a hash of the address is trivially forged, so it can
  neither group players reliably nor protect anyone. Backends still validate
  its syntax.
* `in_world:false` (e.g. main menu, loading screen, disconnect) removes the
  session from all routing; `network_id`/`world_id` MAY then be omitted.
* On a new scope the backend MUST atomically: clear the session's position,
  clear its visible-peer set (rev := 0), clear presence, and start routing only
  with packets whose `epoch` equals the new epoch.
* An `epoch` ≤ the current epoch is answered with `stale_epoch` and ignored.

#### 6.3.1 Scope attestation (optional companion plugin)

If the Minecraft network runs the optional companion plugin, the client may
attach `"attestation":"<payload_b64url>.<mac_b64url>"` to `scope`. `payload`
is JSON `{"v":1,"network":"<name>","subserver":"<name>","player":"<uuid>","iat":<unix>}`,
`mac = HMAC-SHA256(key[network], payload_bytes)`. Keys are configured on the
backend (`SCOPE_ATTESTATION_KEYS=name:base64key,...`). If valid (known
network, correct MAC, `player` equals the session UUID, |now − iat| ≤ 300 s),
the session carries the attested scope `<network>/<subserver>`. Routing
then additionally requires that **both** sides, if both are attested, carry
the same attested scope (§8, check 5). Invalid attestations are ignored
(logged at debug) — never fatal.

### 6.4 Position

```json
{"type":"pos","epoch":7,"x":100.5,"y":64.0,"z":-20.25}
```

At most `position_hz_max` per second (backends drop extra ones silently). The
rate window restarts on every accepted `scope`, so the first `pos` of a new
epoch is always accepted even when it follows the previous epoch's last `pos`
closely.
Ignored when `epoch` ≠ current epoch or the session is not in a world.
Coordinates must be finite and |v| ≤ 3.0e7. Positions are **only** a routing
hint for bandwidth reduction; receivers never use them for playback (§9).

### 6.5 Visible peers

The client reports the UUIDs of player entities its Minecraft world currently
tracks within `max_range` (the local player excluded).

Full set:

```json
{"type":"peers","epoch":7,"rev":1,"full":["…uuid…","…uuid…"]}
```

Delta (capability `peers_delta`):

```json
{"type":"peers_delta","epoch":7,"base":1,"rev":2,"add":["…"],"remove":["…"]}
```

* `rev` is a u32 chosen by the client, strictly increasing within an epoch.
* A delta whose `base` ≠ the backend's current `rev` for that epoch is
  rejected and answered with `{"type":"peers_resync","epoch":7}`; the client
  MUST then send a full set.
* The client SHOULD send deltas at most every 250 ms and a full set at least
  every 30 s.
* The set is bounded to 512 entries; more is `bad_message`.

### 6.6 State

```json
{"type":"state","muted":false,"deafened":false}
```

A muted session's voice is not routed. A deafened session receives nothing.

### 6.7 Presence (backend → client, capability `presence`)

```json
{"type":"presence","epoch":7,"add":["…uuid…"],"remove":["…uuid…"]}
```

Lists, **restricted to UUIDs the recipient itself reported as visible**, which
of those players currently hold a healthy MCVoice cloud session that is
compatible with the recipient's (§8 check 5) and reports the recipient as
visible in turn (mutual visibility, §8 checks 7-8). Used by the client-side transport deduplication (§10). Presence never
reveals players the client does not already see in its own world.

### 6.8 Heartbeat

`{"type":"ping","nonce":42}` → `{"type":"pong","nonce":42,"server_time":1727200000123}`.

### 6.9 Voice key rotation (backend → client, capability `key_rotation`)

```json
{"type":"key","key_id":1,"key":"…base64…","key_expires_in":600}
```

See §7.6.

### 6.10 UDP confirmation

After the backend validates the first UDP `HELLO` (§7.4) it sends
`{"type":"udp_ok"}` over control. Clients that do not receive it within 5 s
retry `HELLO` with back-off and report "UDP blocked" if it never arrives.

### 6.11 Bye

`{"type":"bye"}` from either side, then close.

## 7. Voice datagrams (UDP)

Default port: **24455/udp** (distinct from Simple Voice Chat's default).

### 7.1 Header (22 bytes, authenticated as AAD)

| offset | size | field | value |
|-------:|-----:|-------|-------|
| 0 | 2 | magic | `0x4D 0x56` ("MV") |
| 2 | 1 | version | protocol major, `0x01` |
| 3 | 1 | type | see 7.2 |
| 4 | 1 | flags | reserved, MUST be 0 |
| 5 | 1 | key_id | voice key identifier |
| 6 | 8 | connection_id | u64 big-endian |
| 14 | 8 | counter | u64 big-endian, AEAD nonce counter |
| 22 | n | ciphertext‖tag | AES-128-GCM, 16-byte tag |

All multi-byte integers are big-endian.

### 7.2 Datagram types

| type | dir | plaintext |
|-----:|-----|-----------|
| `0x01` HELLO | C→S | `player_uuid[16]` ‖ `client_time_ms u64` |
| `0x02` HELLO_ACK | S→C | `client_time_ms u64` (echo) |
| `0x10` VOICE | C→S | §7.3 |
| `0x11` VOICE_RELAY | S→C | §7.3 |
| `0x20` PING | C→S | `client_time_ms u64` |
| `0x21` PONG | S→C | `client_time_ms u64` (echo) |

Datagrams of any other type are dropped (counted as invalid).

### 7.3 Voice payloads

`VOICE` (client → backend):

| offset | size | field |
|------:|----:|-------|
| 0 | 4 | epoch (u32) – sender's current world-session epoch |
| 4 | 2 | sequence (u16, wraps) |
| 6 | 4 | timestamp (u32, 48 kHz sample clock, wraps) |
| 10 | 1 | codec (`1` = Opus) |
| 11 | 1 | mode (`0` normal, `1` whisper) |
| 12 | 1 | flags (bit 0 = end of transmission; others MUST be 0) |
| 13 | 2 | payload_length (u16, ≤ 1000) |
| 15 | n | Opus payload (exactly payload_length bytes) |

`VOICE_RELAY` (backend → client):

| offset | size | field |
|------:|----:|-------|
| 0 | 16 | sender UUID |
| 16 | 4 | recipient_epoch – the *recipient's* epoch used for the routing decision |
| 20 | 4 | sender_epoch |
| 24 | 2 | sequence |
| 26 | 4 | timestamp |
| 30 | 1 | codec |
| 31 | 1 | mode |
| 32 | 1 | flags |
| 33 | 2 | payload_length |
| 35 | n | Opus payload |

The backend copies sequence/timestamp/codec/mode/flags/payload unchanged.
A frame with `payload_length = 0` and the end-of-transmission flag set is a
valid "stop talking" marker.

### 7.4 AEAD

* Algorithm: **AES-128-GCM** (NIST SP 800-38D), 16-byte tag. Chosen because it
  is available in every Java 8 runtime shipped with old Minecraft launchers
  (including runtimes without the unlimited-strength policy), in RustCrypto
  (`aes-gcm`) and in Go's standard library.
* Nonce (12 bytes): `direction[1] ‖ key_id[1] ‖ 0x00 0x00 ‖ counter[8]`, where
  `direction = 0x43 ('C')` client→backend and `0x53 ('S')` backend→client.
  Because client and backend share the key, the direction byte guarantees the
  two directions never reuse a nonce.
* AAD: the 22 header bytes.
* A sender MUST NOT reuse a counter for a given (key, direction). Counters start
  at 1 for each new key. A sender that would wrap MUST stop and request a new
  session (never happens in practice: 2^64).

### 7.5 Replay protection

Each receiver keeps, per (connection, key_id), the highest counter seen and a
sliding window bitmap of **1024** counters (RFC 6479 style):

* counter = 0 → reject;
* counter > highest → accept, shift window;
* highest − counter ≥ 1024 → reject (too old);
* bit already set → reject (replay);
* else accept and set bit.

The window is only updated **after** successful AEAD verification.

### 7.6 Key rotation

* The backend rotates the key every `KEY_ROTATION_SECONDS` (default 600):
  it generates key `k+1`, sends `key` over control, and keeps accepting key `k`
  for 30 s.
* The client switches its send key as soon as it receives the `key` message
  and keeps accepting key `k` for 30 s.
* The backend switches its *send* key to `k+1` only after it has authenticated
  a client datagram under `k+1` (proof that the client has it), or after the
  30 s grace period.
* Keys are also bounded by `key_expires_in`; a client whose key expired
  without rotation MUST reconnect (resume).

### 7.7 Amplification and address handling

* The backend never answers a datagram that fails authentication.
* The backend learns a session's UDP address only from an authenticated
  `HELLO`, and updates it (NAT rebinding) only on an authenticated datagram
  with a counter higher than any seen before.
* Every response (`HELLO_ACK`, `PONG`) is no larger than the request.
* `VOICE_RELAY` is only sent to addresses that completed `HELLO`.

## 8. Backend routing (normative)

For every authenticated `VOICE` datagram from sender **S** the backend applies
the following, in order; the first failing check drops the packet:

1. S is authenticated, UDP-verified, not muted (self or admin) and not banned.
2. S is in a world (`in_world`) and `packet.epoch == S.epoch`.
3. S has a position newer than 3 000 ms.
4. `range = mode == whisper ? whisper_range : normal_range`; `range = min(range, max_range)`.

Then for every other session **R** (R ≠ S):

5. Compatible scopes: `R.world_id == S.world_id`, and if **both** R and S
   carry an attested scope (§6.3.1), those are equal. `network_id` is not
   compared (§6.3).
6. R is in a world, UDP-verified, not deafened, has a position newer than 3 000 ms.
7. **S's UUID is in R's visible-peer set** for R's current epoch.
8. **R's UUID is in S's visible-peer set** for S's current epoch. Mutual
   visibility is mandatory; it is not configurable.

Mutual visibility is what ties routing to the actual game: a player UUID is
authenticated by Mojang (§6.2), a player is on one server at a time, and an
honest client reports only entities its own world tracks. Two players are
routed only when *both* games show the other player's entity — independent
of the addresses they used to connect. A client lying about its visible set
gains nothing unless its victim's own client reports it back.

Implementations SHOULD find candidates through S's visible set (at most 512
UUIDs) and a UUID index, not by scanning all sessions.
9. Euclidean distance(S.pos, R.pos) ≤ range + `ROUTING_DISTANCE_SLACK` (default 4 blocks,
   absorbs position-update latency).

If all hold, the backend sends `VOICE_RELAY` to R with `recipient_epoch = R.epoch`.

This check is **defence in depth**, not a security boundary: a modified client can
lie about its position or visible set. The receiving client's own check (§9) is the
final authority for playback.

## 9. Client playback rule (normative)

> If the speaker does not currently exist as a tracked player entity in my
> current local Minecraft world, do not play positional audio from that speaker.

Before playing any positional frame (from any transport, including Simple Voice
Chat interop) the receiving client MUST check, against its *own* Minecraft state
at playback-decision time:

1. The client is in a world and the frame's `recipient_epoch` (cloud) equals the
   client's current epoch.
2. The sender UUID is a currently tracked player entity in the current local world.
3. That entity is in the same dimension/world as the local player.
4. Distance(local player, sender entity) computed from **local** entity positions
   ≤ the voice range for the frame's mode.
5. The sender is not locally muted/blocked, and the local user is not deafened.

Positions or distance claims from the backend or the remote player are never used
for this decision. When a check starts failing mid-stream the client fades the
stream out over ≤ 20 ms instead of cutting it hard.

## 10. Transport identity and deduplication

A client may receive the same speaker through two transports:

* `CLOUD` – this protocol,
* `SVC` – the client's own Simple Voice Chat compatibility layer (see
  `docs/svc-interop.md`).

Deduplication is keyed by the **Minecraft player UUID** of the speaker, never by
audio content. Per speaker the client tracks cloud availability (`presence` +
recent healthy cloud frames) and SVC availability (recent SVC frames). Selection:

```
if cloudPeer(sender) && cloudHealthy(sender):  accept CLOUD, suppress SVC
elif svcActive(sender):                        accept SVC
elif cloudActive(sender):                      accept CLOUD
else:                                          nothing
```

The complete state machine, including hysteresis and switch-over fades, is in
[`dedup-state-machine.md`](dedup-state-machine.md).

## 11. Reconnect

* Control loss: the client keeps the Minecraft session untouched, marks voice as
  `RECONNECTING`, and reconnects with exponential back-off (1, 2, 4, 8, 16, 30 s cap,
  ±20 % jitter), using `resume` while the token is valid.
* After a successful (re)connect the client MUST send a new `scope` with a new
  epoch, a full `peers` set and a position.
* UDP silence (no `PONG`/`VOICE_RELAY` for 15 s while control is healthy)
  triggers a new `HELLO`.

## 12. Privacy

* Voice payloads are never stored or logged by the backend.
* Positions are only kept in memory for routing and never written to normal logs.
  Position logging requires `LOG_POSITIONS=true` *and* `LOG_LEVEL=debug`.
* The backend keeps in memory: UUID, username, world id and attested scope, epoch, last position,
  visible-peer set, UDP source address, counters, and statistics — for the
  lifetime of the session only.
* Persistent data (only if configured): ban list (`BANS_FILE`).
