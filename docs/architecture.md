# Architecture

MCVoice is a proximity voice chat for Minecraft Java Edition. It has three parts:

1. a **client mod**: one version-independent Java 8 core, plus a thin adapter
   per Minecraft API family and loader;
2. a **voice backend**: a Rust and a Go implementation of the same protocol,
   interchangeable;
3. an optional **Simple Voice Chat interoperability** layer inside the client
   (see [svc-interop.md](svc-interop.md)).

The Minecraft server is never on the voice path, and it needs no plugin.

```
            ┌───────────────────────── Minecraft client (mod) ─────────────────────────┐
            │  platform adapter (per MC family + loader)                              │
            │   McAdapter · key/HUD/screen hooks · plugin channels                    │
            │        │ LocalPlayerState, WorldAdapter, NetworkDetectionAdapter        │
            │        ▼                                                                │
            │  core (Java 8, no Minecraft classes)                                    │
            │   WorldTracker ──► WorldSnapshot ──► PlaybackValidator (every frame)    │
            │   PeerReporter      ControlClient (WSS)     CloudVoiceChannel (UDP)     │
            │   AudioEngine: capture → Opus (Concentus) ─┬─► cloud                   │
            │                                            └─► SVC interop (optional)  │
            │   receive → TransportSelector (dedup) → JitterBuffer → SpatialMixer     │
            └──────────────────────────────┬───────────────────────┬─────────────────┘
                              WSS control  │                       │ UDP voice (AES-GCM)
                                           ▼                       ▼
                         ┌──────────── MCVoice backend (Rust or Go) ────────────┐
                         │ control: auth (Mojang hasJoined), scope, peers,       │
                         │          key rotation, presence, rate limits          │
                         │ voice:   replay window → routing (§8) → relay         │
                         │ /health /ready /metrics                               │
                         └──────────────────────────────────────────────────────┘
```

## Repository layout

| Path | Contents |
|---|---|
| `protocol/specification/` | Normative protocol v1 and the dedup state machine |
| `protocol/test-vectors/` | Byte-exact vectors plus the reference generator (`generate.py`) |
| `backend/rust/` | Tokio + axum backend |
| `backend/go/` | Go backend, the black-box conformance suite (`pkg/conformance`) and a Go test client |
| `client/common` | Platform interfaces, proximity (world tracker, validator), config, transport selection |
| `client/network` | UDP datagram codec/cipher/replay window, WebSocket client, control client |
| `client/audio` | Vendored Concentus Opus (BSD-3, relocated), jitter buffer, spatial mixer, Java Sound |
| `client/svc-compat` | Independent Simple Voice Chat protocol implementation |
| `client/ui` | Self-drawn settings, status and HUD, rendered through `UiCanvas` |
| `client/core` | `VoiceClient`: wires everything and runs from the client tick |
| `client/platform/<family>/` | Minecraft-facing sources for a range of versions (`families.json`) |
| `tools/port-version/` | Generates the per-version Gradle builds (`minecraft/`) and validates jars |
| `tools/versions/` | Builds `versions/versions.json` from official metadata, collects build status |
| `tools/load-test/`, `tools/protocol-tests/` | Load generator, cross-implementation test runner |
| `deployment/` | docker compose (with Caddy for TLS), Kubernetes manifests |

## Client

### Core and adapters

Everything below `client/platform` is Minecraft-agnostic and compiled with
`--release 8`, so the same bytecode runs on Minecraft 1.8.9 (Java 8) and on
26.x (Java 25). A platform adapter implements a few narrow interfaces:

* `MinecraftAdapter`: version, loader, config directory, local player;
* `WorldAdapter`: dimension id, world identity, and iteration over **tracked
  player entities**;
* `NetworkDetectionAdapter`: multiplayer, server address, brand, remote socket address;
* `SessionAuthenticator`: Mojang `joinServer` for backend authentication (the
  access token goes only to Mojang's session server);
* `InputAdapter`, `GuiAdapter` (`UiCanvas`/`UiScreen`), `SimpleVoiceChatAdapter`
  (raw plugin channels).

Loader entry points (`McVoiceFabric`, `McVoiceForge`) register keys, the HUD
layer, tick and disconnect hooks, and then call `VoiceClient.clientTick()`.
Two adapter families exist: `mojang` (official mappings, 1.16.1–26.3) and `legacy` (MCP and Legacy Yarn names, 1.8–1.12.2).
Differences between versions inside a family are `//#if MC >= x` blocks,
resolved at generation time by `tools/port-version/preprocess.py`. See
[version-porting.md](version-porting.md).

### Per-tick flow

1. `WorldTracker` builds an immutable `WorldSnapshot` from the adapters: local
   player, dimension, tracked players and positions. It also detects
   world-session changes (server change, JoinGame/entity id change, dimension,
   world object replaced, respawn, left world), and each change starts a new
   **epoch**.
2. `ControlClient` sends `scope` on each new epoch, `pos` at up to 10 Hz while moving (1 Hz keep-alive while standing still), and
   `peers`/`peers_delta` from `PeerReporter` (the UUIDs of locally tracked players).
3. The audio thread captures 20 ms frames. `CaptureProcessor` applies gain,
   VAD or push-to-talk and the whisper range, then encodes Opus once and sends
   it to every active transport.
4. Received frames go to `TransportSelector` (one transport per speaker; see
   [dedup-state-machine.md](../protocol/specification/dedup-state-machine.md)),
   then to a per-speaker `JitterBuffer`. `SpatialMixer` pulls one frame per
   speaker every 20 ms. **Before mixing it re-runs `PlaybackValidator`**
   against the newest snapshot: the speaker must be a tracked player entity in
   the current world, in range, and not muted or deafened. Panning and
   attenuation use local entity positions only. Voice-group frames (mode 2)
   instead need the speaker in the current group member list and are mixed
   centred.

Proximity security is explained in [proximity-security.md](proximity-security.md).

## Backend

Both implementations share configuration (environment variables, see
`backend/*/.env.example`), endpoints, metrics names and behaviour. They are
tested by the same black-box suite.

* **Control** (`/v1/control`, WebSocket): hello with a challenge. Auth by
  Mojang `hasJoined` (production) or offline (development), or resume with an
  HS256 token. Then session keys, scope/epoch handling, positions, peer
  visibility, state, presence, key rotation (30 s grace), heartbeats and
  moderation (bans/mutes file).
* **Voice** (UDP): fixed 22-byte header as AAD; AES-128-GCM with a direction-
  and key-scoped nonce; a 1024-packet replay window. Routing is the pure
  function from spec §8: same dimension (and attested sub-server, if both are
  attested), mandatory mutual visibility, distance ≤ range + slack, fresh positions. The backend relays one
  datagram per eligible recipient, re-encrypted with the recipient's key and
  tagged with the **recipient's** epoch.
* **State** is in memory, in one process. Players who should hear each other
  must be connected to the same backend instance; scale by running one
  instance per community or network. Shared state across replicas (Redis) is
  reserved for a later version, and `REDIS_URL` is ignored in 0.1.x.
* **Privacy**: no audio is stored. IP addresses are pseudonymised (keyed hash)
  in logs. Positions are never logged unless `LOG_POSITIONS=true` and
  `LOG_LEVEL=debug`. Clients never learn other clients' IP addresses.

## Testing layers

| Layer | What | Where |
|---|---|---|
| Vectors | Byte-exact datagram, replay, control, routing, playback and dedup cases | `protocol/test-vectors`, run by Java, Go and Rust tests |
| Backend unit + fuzz | Parsers, routing, rate limits | `go test -race`, `cargo test`, Go fuzzing |
| Conformance | 30 black-box scenarios over real sockets against either backend | `mcvoice-conformance -- <backend binary>` |
| Client end-to-end | Headless `VoiceClient`s with a fake Minecraft against a real backend (proximity, stereo, dimension, proxy sub-server switch, range, hybrid SVC with dedup) | `client/core` tests with `MCVOICE_BACKEND_BIN` |
| Load | N synthetic clients, delivery ratio and latency percentiles | `tools/load-test` |
| Minecraft builds | Every implemented loader of every version compiles and its production jar validates | `mc-build.yml` |
