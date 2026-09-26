# MCVoice

Proximity voice chat for Minecraft Java Edition that works **without any
server plugin**. Voice is relayed through an MCVoice backend, never through
the Minecraft server. Players who use the ordinary **Simple Voice Chat** mod
can still be heard on servers that run the Simple Voice Chat plugin.

<img src="docs/images/icon.png" width="96" alt="MCVoice icon">

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
* **Voice groups.** Up to 15 players hear each other everywhere, even on
  different servers: search and join active groups, or create one with an
  optional password (hotkey **G**). Group members hear you while your
  microphone is on; nearby players still need push-to-talk, and nobody hears
  you twice.
* Two interchangeable backends, in **Rust** (Tokio/axum) and **Go**, that
  pass the same black-box conformance suite.
* Minecraft **1.8 → 26.3** on Forge, Fabric and Legacy Fabric (gaps listed with reasons). Every
  supported jar is built and validated by CI (see below).

## Supported Minecraft versions

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
3. Default keys:
   * **V**: push to talk;
   * **B**: whisper;
   * **M**: mute;
   * **N**: deafen;
   * **G**: voice groups.

   Settings and status screens can be bound in Controls.
4. The HUD shows your microphone bottom left and who is talking top left
   (muted players greyed out). Open the chat and click a name to change that
   player's volume or mute them.
5. Settings live in `config/mcvoice.json`:
   * backend URL (default: the public backend `wss://mcvoice.ravoxx.dev/v1/control`);
   * activation mode (push-to-talk or voice activation) and its threshold;
   * gain, devices and distances;
   * per-player volume and mute;
   * HUD and debug options.

Audio is never recorded or stored by the mod or by the backend.

## For backend operators

```sh
cd deployment/compose
cp ../../backend/rust/.env.example .env   # set JWT_SIGNING_SECRET, SESSION_SECRET, PUBLIC_HOSTNAME
docker compose up -d                      # backend + Caddy (TLS for wss://), UDP 24455
```

Images: `ghcr.io/ravoxx/mcvoice/voice-backend-rust` and
`…/voice-backend-go`. Kubernetes manifests, configuration, scaling and
monitoring: [backend deployment](docs/backend-deployment.md). Never commit a
real `.env`.

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
