# MCVoice

Proximity voice chat for Minecraft Java Edition that works **without any
server plugin**. Voice is relayed through an MCVoice backend, never through
the Minecraft server. Players who use the ordinary **Simple Voice Chat** mod
can still be heard on servers that run the Simple Voice Chat plugin.

<img src="docs/images/icon.png" width="96" alt="MCVoice icon">

Stable **[0.1.13](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.13)** fixes
five security boundaries in UDP replay, group ownership, SVC encryption/replay,
and API probing. The public backend is updated. See [release verification](docs/release-0.1.13.md)
and [SVC renewal behavior and limits](docs/security-0.1.13.md).

* Positional 3D audio (equal-power stereo panning, distance attenuation),
  Opus at 48 kHz in 20 ms frames, a jitter buffer and a whisper mode.
* **The local entity rule.** Positional voice from a speaker is played only
  while they currently exist as a tracked player entity in the listener's own
  Minecraft world, within range. This holds on proxy networks
  (Velocity/BungeeCord) where sub-servers share an address, dimension names
  and coordinates. The one exception is voice groups, which members join on
  purpose. See [proximity security](docs/proximity-security.md).
* Encrypted voice (AES-128-GCM with replay protection and key rotation) and
  Minecraft account verification through Mojang's session server. Access
  tokens never leave the client towards our backend.
* **Hybrid Cloud + Simple Voice Chat.** An independent implementation of the
  SVC client protocol reaches SVC-only players. MCVoice users connected both
  ways are heard exactly once (sender-aware deduplication), and the mod falls
  back to SVC when the cloud is unreachable. See [SVC interop](docs/svc-interop.md).
* **Voice groups.** Up to 20 players hear each other everywhere, even on
  different servers: search and join active groups, or create one with an
  optional password (Voice Chat > Groups). The menu loads 20 groups at a time and
  fetches more as you scroll; searching starts a fresh page of matching codes.
  Groups use the same push-to-talk or voice-activation mode as nearby speaking.
  **Normal** groups keep your speech inside the group while letting you hear
  nearby outsiders; **Open** groups also speak nearby; **Isolated** groups hear
  only their members. Normal is the default. These choices require an updated
  backend; older backends retain explicitly selected legacy Open groups.
* Two interchangeable backends, in **Rust** (Tokio/axum) and **Go**, that
  pass the same black-box conformance suite.
* Minecraft **1.8 → 26.3** on Forge, Fabric and Legacy Fabric (gaps listed with reasons). Every
  supported jar is built and validated by CI (see below).

## Supported Minecraft versions

**[0.1.13](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.13)** is published
for all 62 supported Minecraft versions (102 validated jars), with both backend
images and 102 byte-verified Maven publications. The public Rust backend runs
0.1.13. See [the release evidence](docs/release-0.1.13.md).

The authoritative, CI-generated matrix is
[`versions/supported.md`](versions/supported.md) (machine-readable:
`versions/versions.json` + `versions/build-status.json`). A version and loader
is listed as supported **only** when CI built and validated its jar.
Versions not built are marked with the reason, for example no upstream
loader, or not implemented yet.

Download the jar for your Minecraft version and loader from
[Releases](https://github.com/RavoxX/MCVoice/releases). Each tag is
`v<version>-mc<minecraft>`, and each release includes `checksums-sha256.txt`.

## For players

1. Put the jar into `mods/`. Fabric needs Fabric API. Legacy Fabric needs
   Legacy Fabric API on the versions that have it (1.8, 1.8.9, 1.9.4, 1.10.2,
   1.11.2, 1.12.2); on 1.8.1–1.8.8 the mod works without it.
2. Join any server. Nothing is needed on the server.
3. Press **V** for the voice menu. Select your audio devices in Settings > Audio
   and choose push-to-talk or voice activation in Settings > General. In
   **Key Bindings**, choose your talk key and optionally a whisper key; keyboard
   and mouse buttons are supported. Talk and whisper start unbound.
   * **M**: mute/unmute in voice-activation mode;
   * **N**: disable/enable voice chat (both speaking and listening);
   * **H**: hide/show voice icons;
   * **G**: groups on Minecraft 1.21.5 and earlier; unbound on 1.21.6+.

   All bindings also appear in Minecraft's Controls. Joining a group does not
   automatically turn on voice activation. Whisper uses the same group routing
   policy: it never makes a Normal/Isolated group audible to nearby outsiders.
4. The HUD shows who is talking top left, including yourself when your microphone
   detects voice while transmitting. Your microphone appears bottom right while
   push-to-talk is held or voice activation is enabled (including group open mic):
   grey without voice activity, green while speaking. Muting or deafening shows
   a red crossed icon while the indicator is visible. Open the chat and click another
   player's name to change their volume or mute them.
   The adapters preserve server name colours and prefixes in the
   HUD and add a green microphone beside visible speaking players' name tags.
   Compact, shaded pixel icons use a 10-pixel grid in speaker rows and an 8-pixel
   grid on name tags. The microphone indicator defaults to 16 pixels, using the
   same shaded artwork. Older adapters draw the same pixel artwork directly, preserving vanilla
   name-tag visibility and range checks. Fabric name-tag hooks are optional: if
   another mod prevents a hook from applying, its icon can be skipped without
   a required-injection startup error. Essential HUD and tick hooks stay required.
   These indicators are rendered by MCVoice on the listener's client.
   In a voice group, every member's head appears at the right edge, including
   quiet members. A speaking member gets a white outline and their name to the
   left. Unknown skins use Minecraft's default skin. **Group HUD** in General
   settings is on by default; turn it off to hide the roster.
5. Settings live in `config/mcvoice.json`:
   * backend URL (default: the public backend `wss://mcvoice.ravoxx.dev/v1/control`);
   * activation mode (push-to-talk or voice activation) and its threshold;
   * gain, devices and distances;
   * per-player volume and mute. The **Players** tab searches online voice users
     by name and keeps formatted names aligned with their controls. It lists
     confirmed MCVoice peers in the local world, current group members, and
     connected SVC users reported by the server, including quiet users;
   * HUD and debug options. **Edit HUD** in General settings lets you drag the
     speaker list and microphone, choose a vertical or horizontal list, toggle
     its background, and set microphone size (10–32 pixels). Done saves the
     layout. The group roster can be dragged independently, with its own
     background toggle and head size (10–32 pixels, fitted down for large groups
     on small screens). Cancel or Escape discards edits; Reset restores defaults in the editor.
     Positions follow changes in window size and GUI scale.

Audio is never recorded or stored by the mod or by the backend.

## For backend operators

```sh
cd deployment/compose
cp ../../backend/rust/.env.example .env   # set secrets, PUBLIC_HOSTNAME and TRUSTED_PROXY_CIDRS
docker compose up -d                      # backend + Caddy (TLS for wss://), UDP 24455
```

Images: `ghcr.io/ravoxx/mcvoice/voice-backend-rust` and
`…/voice-backend-go`. Kubernetes manifests, configuration, scaling and
monitoring: [backend deployment](docs/backend-deployment.md). Never commit a
real `.env`.

Compose requires an explicit trusted Caddy peer or dedicated proxy-network CIDR.
See [proxy configuration and first startup](docs/backend-deployment.md#docker-compose)
before starting the backend; do not trust client-supplied forwarding headers.

## Repository layout

| Path | What |
|---|---|
| `protocol/` | Normative protocol v1, dedup state machine, byte-exact test vectors |
| `backend/rust`, `backend/go` | The two backends (Dockerfiles, `.env.example`) |
| `client/` | Version-independent Java 8 client core (network, audio, SVC compat, UI, proximity) |
| `client/platform/` | Minecraft adapters per API family (`mojang` for 1.16.1+, `legacy` for 1.8–1.12.2) |
| `tools/port-version/` | Generates the per-version Gradle builds, validates jars, creates `mc/<version>` branches |
| `tools/versions/` | Version matrix from official metadata; CI build status |
| `tools/load-test/`, `tools/protocol-tests/`, `tools/svc-interop/` | Load generator, cross-implementation tests, real SVC server probe |
| `deployment/` | Docker Compose and Kubernetes |
| `docs/` | Documentation |

Branch `main` holds all of the above. Each `mc/<version>` branch is `main`
plus the generated `minecraft/` build for that version.

## Building and testing

```sh
# protocol vectors (Python + cryptography)
python3 protocol/test-vectors/generate.py --check
# backends
(cd backend/go && go test -race ./...)
(cd backend/rust && cargo test --release)
# conformance suite against either backend
(cd backend/go && go run ./cmd/mcvoice-conformance -- ../rust/target/release/mcvoice-backend)
# client core + headless end-to-end tests against a real backend
(cd client && MCVOICE_BACKEND_BIN=/path/to/mcvoice-backend ./gradlew build)
# a Minecraft build
python3 tools/port-version/port.py 1.20.1 && (cd minecraft/fabric && ./gradlew build)
```

CI workflows:

| Workflow | Purpose |
|---|---|
| `ci.yml` | Everything on `main` except Minecraft jars |
| `backend.yml` | Container images and GHCR |
| `mc-build.yml` | Per-version Minecraft builds |
| `version-matrix.yml` | Refreshes the version matrix |
| `svc-interop.yml` | Real Simple Voice Chat server check |
| `release.yml` | Releases |
| `mc-probe.yml` | API lookup for porting |
| `mc-smoke.yml` | Client runtime smoke test: starts the real client headlessly with the mod and joins a world (dispatch only) |

## Documentation

* [Architecture](docs/architecture.md)
* [Protocol overview](docs/protocol.md) and [specification](protocol/specification/mcvoice-protocol-v1.md)
* [Proximity security](docs/proximity-security.md)
* [Simple Voice Chat interoperability](docs/svc-interop.md)
* [Version porting](docs/version-porting.md)
* [Backend deployment](docs/backend-deployment.md)
* [Releasing](docs/releasing.md)

## Security and privacy

* No voice recording and no raw audio retention.
* No player IP addresses exposed to other clients.
* No exact positions in production logs.
* No Microsoft/Minecraft access tokens in any MCVoice packet.

Found a vulnerability? Please open a private security advisory on GitHub.

## License and third parties

MIT, see [LICENSE](LICENSE). Bundled: [Concentus](https://github.com/lostromb/concentus)
(pure-Java Opus, BSD-3-Clause), relocated to `dev.mcvoice.thirdparty.concentus`.
Simple Voice Chat is a separate project by its authors. MCVoice is not
affiliated with it and contains none of its code or assets. The
interoperability layer is an independent implementation, tested against
the unmodified SVC server.
