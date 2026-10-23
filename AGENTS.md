# AGENTS.md — working on MCVoice

Guide for AI agents (and humans) contributing to this repository. Read it
before changing anything. User-facing docs live in `README.md` and `docs/`;
this file is about **how to work here**.

## What this is

A proximity voice chat for Minecraft Java Edition (1.8 → 26.3) that needs **no
server plugin**:

* **Backend** (`backend/rust`, `backend/go`): two interchangeable
  implementations of one protocol. The Minecraft server is never on the voice
  path.
* **Client**: a version-independent Java 8 core (`client/`), plus thin adapters
  per Minecraft API family and loader (`client/platform/<family>/`).
* **Simple Voice Chat (SVC) interop**: an independent re-implementation of the
  SVC client protocol (`client/svc-compat`), verified against the real SVC
  server in CI.

Normative protocol: `protocol/specification/mcvoice-protocol-v1.md` +
`protocol/test-vectors/`. If code and spec disagree, fix one of them, then
regenerate the vectors.

## Non-negotiable rules

1. **Local entity rule.** Never play positional audio from a speaker who
   is not a currently tracked player entity in the listener's current local
   world, in range. `PlaybackValidator` is checked on every positional frame
   of every transport. Never rely on server address, dimension name or
   coordinates alone (proxy sub-servers share all three). The only
   non-positional audio is voice groups: played centred, and only from members
   of the listener's current group (spec 9.1). See `docs/proximity-security.md`.
2. **Never fake success.**
   * A Minecraft version/loader counts as supported only when CI built and
     validated its jar (`versions/build-status.json`).
   * Report exact failures. Never mark failing builds as done.
   * Never claim a publish that did not happen.
   * Never skip or disable tests to get green.
3. **No secrets in git.**
   * Never commit `.env` (only `.env.example`), keys, tokens or certificates.
     CI runs gitleaks and a tracked-file check.
4. **Privacy.**
   * No voice recording and no raw audio retention.
   * No exact positions in normal logs.
   * Never expose player IPs to other clients.
   * Never put Minecraft/Microsoft access tokens in MCVoice packets. The token
     only goes to Mojang's `joinServer`.
5. **No Simple Voice Chat code, assets, icons or sounds** in this repository.
   The SVC server used by CI is downloaded at run time.
6. **No custom crypto.** AES-128-GCM via standard libraries only.
7. `client/` core modules stay **Java 8** (`--release 8`, no Minecraft classes).
   Only `client/platform/**` touches Minecraft.

## Repository map

| Path | Purpose |
|---|---|
| `protocol/` | Spec, dedup state machine, test vectors (`generate.py --check` needs Python `cryptography`) |
| `backend/go` | Go backend, black-box conformance suite (`pkg/conformance`, `cmd/mcvoice-conformance`), Go test client |
| `backend/rust` | Rust backend (Tokio/axum) |
| `client/{common,network,audio,svc-compat,ui,core}` | Java 8 client core (Gradle 8.14 wrapper in `client/`) |
| `client/platform/families.json` | Which adapter sources + build setup serve which Minecraft versions |
| `client/platform/mojang/` | Official-mappings family, 1.16.1–26.3: `common/`, `fabric/`, `forge/` (EventBus 7, 1.21.6+), `forge-eb6/` (1.19–1.21.5), `forge-fml/` (1.16.1–1.18.2) |
| `client/platform/legacy/` | 1.8–1.12.2: `mcp/` + `forge/` (MCP names), `yarn/` + `fabric/` (Legacy Fabric, Legacy Yarn names) |
| `client/platform/mcp13/` | 1.13.2 Forge: `common/` + `forge/` (MCP `stable_47-1.13.2` names, 1.13 API) |
| `tools/port-version/` | `port.py` (generate `minecraft/`), `preprocess.py`, `validate_jar.py`, `make-branch.sh`, build templates, class remap tables |
| `tools/versions/` | `generate_matrix.py` (official metadata → `versions/versions.json`), `matrix.py`, `collect_status.sh` |
| `tools/release/report.py` | Renders `release-report.md` from release artifacts |
| `tools/svc-interop/` | Node bot for the real-SVC-server probe (the harness is `client/svc-compat/src/test/.../tools/SvcProbe.java`) |
| `tools/load-test/`, `tools/protocol-tests/` | Load generator, cross-implementation runner |
| `deployment/` | Compose (+ Caddy TLS) and Kubernetes |
| `versions/` | `versions.json`, `build-status.json`, `supported.md` (generated; don't hand-edit) |

## Branch model

* `main`: everything above plus the latest client. Development happens here
  (or on feature branches merged into it).
* `mc/<version>` (one per supported version): `main` + a generated
  `minecraft/` directory. **Never edit these by hand.** Refresh them with
  `tools/port-version/make-branch.sh <mc>|--all-supported [--push]`. The script
  merges `main` and regenerates; history is never rewritten.
* `tooling/probe-output`: CI writes diagnostics here (probe output, build
  logs, per-loader status JSON, SVC probe results).
  `tools/versions/collect_status.sh` reads it. It is not a release branch.

## Everyday commands

```sh
python3 protocol/test-vectors/generate.py --check           # vectors reproducible
python3 tools/versions/matrix.py validate
(cd backend/go && gofmt -l . && go vet ./... && go test -race ./...)
(cd backend/rust && cargo fmt --check && cargo clippy --all-targets --release -- -D warnings && cargo test --release)
# conformance (41 scenarios) against either backend binary
backend/go/mcvoice-conformance -name rust -- backend/rust/target/release/mcvoice-backend
# client core + headless end-to-end tests (needs a backend binary)
(cd client && MCVOICE_BACKEND_BIN=$PWD/../dist/mcvoice-backend-go ./gradlew build)
```

The Minecraft builds need network access to the loader mavens and are
normally run **in CI**, not locally:

```sh
python3 tools/port-version/port.py --list          # plan for every version (loaders + reasons)
python3 tools/port-version/port.py 1.20.1           # writes ./minecraft/<loader>/ standalone Gradle builds
(cd minecraft/fabric && ./gradlew build)            # jar in build/libs/, validate with tools/port-version/validate_jar.py
```

## CI workflows (`.github/workflows/`)

| Workflow | Trigger | What |
|---|---|---|
| `ci.yml` | push/PR | vectors, secrets scan, Go, Rust, conformance vs both, load smoke, client + e2e vs both backends, Java 8 bytecode check |
| `backend.yml` | backend changes on main, `workflow_call` | images, container smoke test, GHCR push (`edge`/`sha-*`; release: `<version>`, `latest` only for stable) |
| `mc-build.yml` | push `mc/**`, dispatch `{minecraft}` | builds each loader of one version with its own JDK, validates the jar, publishes log + status JSON to `tooling/probe-output:builds/<mc>/` |
| `mc-probe.yml` | dispatch | **API lookup for porting**: official signatures (Mojang mappings or unobfuscated jar), extra jars (javap), Forge MDK build files, tiny/CSV mapping blocks, arbitrary URLs. Output → `tooling/probe-output:<mc>/latest.txt` |
| `mc-smoke.yml` | dispatch `{minecraft}` (dispatch only: runs third-party code) | builds the versions, then starts the real client headlessly (headlesshq/mc-runtime-test 4.5.1 + HeadlessMC under Xvfb), joins a world and quits; passes only if MCVoice logged `initialised` and no MCVoice error; results → `tooling/probe-output:smoke/<mc>/` |
| `svc-interop.yml` | svc-compat changes, weekly, dispatch `{minecraft}` | Paper + real SVC plugin, bot + `SvcProbe`; verdict JSON → job summary and `tooling/probe-output:svc-interop/<mc>/` |
| `version-matrix.yml` | tools/versions changes, weekly | regenerates `versions/versions.json` and **commits to main** (pull before pushing!) |
| `release.yml` | dispatch `{version, minecraft, prerelease, images}` | full release; see `docs/releasing.md` |

Triggering from an agent session without `gh`: POST
`https://api.github.com/repos/RavoxX/MCVoice/actions/workflows/<file>/dispatches`
with `Content-Type: application/json` and
`{"ref":"main","inputs":{...}}`. Poll `.../actions/workflows/<file>/runs`.
Artifact downloads may be blocked in sandboxes. That is why diagnostics are
pushed to `tooling/probe-output` (`git fetch origin tooling/probe-output &&
git show FETCH_HEAD:<path>`).

## How to port a Minecraft version (the loop that works)

1. `python3 tools/port-version/port.py --list`: is it in a family range, and
   does the loader exist upstream (`versions/versions.json`)?
2. **Look up real APIs, never guess.** Dispatch `mc-probe.yml` with the
   classes you use (Mojang names), plus `jars` (loader API / Forge universal
   jar), `jar_filter`, `mdk` (the Forge version, to see the ForgeGradle and
   Gradle it expects) and `mappings` (a regex over Yarn/tiny files for Legacy
   Fabric).
3. Add `//#if MC >= x` / `//#elif` / `//#else` / `//#endif` blocks, and
   `FABRIC`/`FORGE`/`LEGACYFABRIC` flags where needed. Inactive lines are
   blanked, so line numbers stay stable. Prefer a boundary you have seen in a
   probe or a compile error.
4. Add or extend a loader build setup in `client/platform/families.json`:
   * `buildgen`, `gradle`, `plugin_version`;
   * optionally `gradle_jdk`, `mappings`, `sources`, `parts`, `reobf`,
     `class_remap`, `gradle_properties`.
5. Dispatch `mc-build.yml` for representative versions, read
   `tooling/probe-output:builds/<mc>/<loader>.log`, fix, repeat. Then build
   the whole range.
6. `bash tools/versions/collect_status.sh`, then commit `versions/`.
   Refresh the branches with `make-branch.sh`.

### Hard-won facts (don't rediscover them)

**Fabric and Loom**
* Loom 1.18 (`fabric-loom` for unobfuscated 26.x, `fabric-loom-remap` for
  older versions) needs Gradle ≥ 9.7 and a Java 25 **Gradle JVM**. That is
  the `gradle_jdk` field; the game can still target 17 or 21.
* Fabric API was split by minor line until 1.18 (`0.42.0+1.16` is for
  1.16.5 only). Its mod id was `fabric` before 1.19.2 and `fabric-api` after
  (templates set `${fabric_api_id}`). Versions without an exact Fabric API
  release are listed as not built.

**Forge 1.16.x and 1.17+**
* Forge 1.16.x needs ForgeGradle 5.1 on Gradle 7.3.3. FG6 sets up the
  workspace, but the game never reaches the compile classpath.
* Before 1.17, Forge's official mappings rename **members only**. Classes
  keep their MCP names; see `remap/forge-mcp-classes-1.16.txt`.
* `EventNetworkChannel.isRemotePresent` exists from Forge 1.16.5 only. SVC
  interop is disabled below that: never send SVC messages to servers that
  might not have SVC.

**Forge 1.20.x and 1.21.x**
* Forge < 1.20.6 runs on SRG names, so `reobfJar` is needed; 1.20.6+ runs on
  official names (`reobf: false`).
* Forge 1.20.6–1.21.7 have no HUD layer registration. The HUD draws from
  `CustomizeGuiOverlayEvent.Chat`.
* EventBus 7 (1.21.6+): mod-bus events use `getBus(context.getModBusGroup())`
  before 1.21.9 and static `BUS` from then on.
* The EventBus 7 adapter also needs `pack.mcmeta`: without it, Forge 26.1.2
  shows a loading warning and cannot load the mod's resources. Its format
  branches are verified against Mojang's client `version.json`; the jar
  validator requires this metadata.

**Legacy Forge (1.8–1.12.x)**
* 1.8.9–1.12.1 build with Essential's architectury-loom; 1.8 and 1.8.8 with
  Unimined 1.4.1.
* 1.12.2 builds with RetroFuturaGradle 2.0.4 (no userdev jar exists; RFG 2
  needs a Java 25 Gradle JVM).
* MCP renames: `getConnection` from 1.9; `player`/`world`/`fontRenderer`
  from 1.10. The font is read via `ingameGUI.getFontRenderer()`.

**Legacy Fabric**
* Without Legacy Fabric API (1.8.1–1.8.8) the adapter uses its own mixins:
  `MinecraftClient#tick/stop/connect(null,…)`, `GameOptions#load` (keys into
  `allKeys`). Preprocessor flag `LEGACYFABRIC_API`; Legacy Yarn build 604
  (603 for 1.8.6). `Window(MinecraftClient)` exists from 1.8.2, not 1.8.1.
* Legacy Fabric API exists only for 1.8, 1.8.9, 1.9.4, 1.10.2, 1.11.2 and
  1.12.2.
* Its API classes live in the `-common` module artifacts.
* It has no HUD callback, so the HUD is a mixin on `InGameHud#render`.

**Forge 1.13.2 (`mcp13`)**
* No Mojang mappings before 1.14.4: MCP `stable_47-1.13.2` (fg6 template,
  FG 5.1 on Gradle 7.3.3, `mappings: "stable_47-1.13.2"`). MCP class names
  are 1.12-style (`GuiScreen`, `EntityPlayerSP`, `WorldClient`,
  `NetHandlerPlayClient`). Translate Mojang member names through SRG ids
  (Mojang 1.14.4 mappings → MCPConfig `joined.tsrg` → MCP CSVs), matching
  methods by descriptor, never by obfuscated name alone.
* 1.13 API: `GuiScreen()` has no title, `mouseScrolled(double delta)`,
  `onGuiClosed`, `doesGuiPauseGame`; `Gui.drawRect`; `World#playerEntities`;
  `TickEvent` is in `net.minecraftforge.fml.common.gameevent`; no
  `ClientPlayerNetworkEvent` (a lost world counts as a disconnect).
  `FMLEnvironment`/`FMLPaths` live in the Forge `launcher` artifact.

**Routing scope**
* Routing never compares `network_id` (the joined address): the same server
  has many addresses (aliases, IPs, tunnels, several proxies, LAN). Scope is
  `world_id` (+ attested sub-server when both sides are attested); mutual
  visibility is mandatory and is what ties routing to the actual game.
  Recipients come from the sender's visible set via the UUID index.

**Voice groups (protocol 1.1)**
* Backend-only state (`backend/*/…/groups`), up to 15 members, 5-char ids
  from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, optional password (salted SHA-256,
  constant-time compare, 5 wrong tries/min), search via `group_list.query`.
  `group_paging` adds id-ordered pages (limit 1–20, cursor, request_id); legacy
  lists retain popularity order and the 100-entry cap. Per-connection group
  budgets: list 2/s burst 4, create 0.1/s burst 3, join 1/s burst 6; leave has
  no extra limiter. Limits run in the control task before hub locking.
  Membership needs `in_world` and ends on disconnect or after 10 s out of a
  world. Group messages are queued in the hub `outbox` under the lock and
  sent after it (never send under the hub lock).
* One frame serves both channels: mode 2 = group only, flag bit 1 on mode
  0/1 = also to the group. Proximity routing skips members of the sender's
  group when group delivery applied (no double audio).
* Client: group channel = open mic gated by VAD while unmuted; proximity
  keeps push-to-talk. Group frames are mixed centred and validated against
  the current member list (`PlaybackValidator` mode 2).
* HUD clicks: `GuiAdapter.chatPointer()` polls the cursor while the chat is
  open (Mojang `mouseHandler.xpos/ypos/isLeftPressed` stable 1.14.4-26.3;
  1.13.2 `mouseHelper.getMouseX/isLeftDown`; legacy LWJGL 2 `Mouse`).

**Releases**
* GitHub Packages never replaces a Maven version: a re-run records `exists`
  (409) for those. A failed publish is retried once; ForgeGradle 7's
  Mavenizer once found its cached `mcp_config` zip truncated.

**Mojang family input**
* Keys use `InputConstants.KEY_*` from 1.20 on (26.3 no longer has LWJGL's
  GLFW on the compile classpath) and `GLFW.GLFW_KEY_*` below.

**Validation**
* `validate_jar.py` prints the platform classes of each jar. Totals vary with
  client changes and target Java: 0.1.8 on 26.3 has 378 Fabric / 377 Forge
  classes; Java 8 targets add synthetic classes where newer targets use
  nestmates. The platform list is what matters.

## Contributing conventions

* **Commits:**
  * Conventional-style subjects: `feat(platform): …`, `fix(port): …`,
    `ci(…)`, `docs: …`, `chore(versions): …`.
  * Explain the *why* in the body.
  * Commit early and often.
* **Push:** `main` gets a bot commit from `version-matrix.yml` whenever
  `tools/versions/**` changes. Always `git fetch origin main && git merge
  origin/main` before pushing, and re-run anything you dispatched from a
  rejected push.
* **Style:** match the surrounding code, keep comments sparse and useful.
  Run gofmt, rustfmt and clippy `-D warnings`, which CI enforces.
* **Changing the protocol:**
  1. Update the spec.
  2. Update `generate.py`, then regenerate and commit the vectors.
  3. Update both backends and the Java client.
  4. Add or adjust conformance scenarios.

  Both backends must pass the same suite.
* **Changing the client core:** keep Java 8. Add a unit or headless e2e test
  (`client/core` tests use `FakeMinecraft` and a real backend binary).
* **Changing SVC compat:** keep it an independent implementation and re-run
  `svc-interop.yml`. Add a version to `SvcProtocols.VERIFIED` only after that
  probe confirms it.
* **New Minecraft support:** follow the porting loop. A version is only
  "supported" after CI passes, and `supported.md` is generated, never
  hand-written.
* **Docs:** `README.md` and `docs/*.md` must match reality. Update them in the
  same change.

## Current state and next work (keep this section updated)

* **Stable 0.1.11 released:** paged `group_directory`
  previews include public UUID/name rosters (password groups included) without
  granting membership/playback. Both backends filter before paging using the
  latest in-world address hash; network ids remain absent from routing checks.
  Group rows show heads/tooltips and two-name summaries; All Groups is the saved
  default. The Java 8 client checks public stable GitHub releases off the game
  and voice-network threads, matches exact Minecraft/loader assets, and offers
  a release link plus opt-out under Settings > Updates. See
  `docs/group-directory-and-updates.md` for coverage and limitations. Source
  `d70ea5d` passes CI `39960283850` (six jobs) and 26.3 runtime `39960281089`
  (Fabric + Forge, including real menu screenshots and native SDL controls).
  Release `39965856784` passes after retrying a Forge 26.2 plugin-resolution
  failure: 102/102 validated jars, 62/62 GitHub Releases, 102/102 Maven
  publications and both images. Every asset digest matches the byte-verified
  Maven jar; both 26.3 jars match the runtime-tested artifacts byte for byte.
  Merged source is `45b0962`; see `docs/release-0.1.11.md`.
  Rust 0.1.11 deployed 2026-10-23 14:08:14 UTC after isolated staging;
  public/local readiness and protocol checks pass, all 30 unrelated containers
  unchanged. See `docs/deployment-0.1.11.md` for evidence and 0.1.10 rollback.

* **Earlier stable 0.1.10:** replay/rotation, trusted proxy identity,
  reconnect password budgets, admission/moderation, concurrent config, group
  transport isolation and protocol edge cases have regression coverage. CI
  test/build permissions are separated from publication. Explicit proxy CIDRs
  are now required when proxy trust is enabled, including repeated XFF fields.
  Final `c10a847` passes local 112-test suites against both backends; CI
  `39284820030` and 26.3 runtime `39284987176` completed successfully.
  Real SVC `39284820339` passes after supporting its authenticated 256-byte ACK
  without relaxing CBC rejection tests. Release `39285852532` passed all 260
  jobs: 102/102 jars, 62/62 GitHub Releases, 102/102 Maven packages, both images.
  All release asset digests match byte-verified Maven jars; both 26.3 jars match
  the runtime-tested artifacts byte for byte. See `docs/release-0.1.10.md`.
  Rust 0.1.10 deployed at
  2026-10-19 16:36:35 UTC after image publication and isolated staging;
  local/public protocol/readiness checks and all 30 unrelated containers pass.
  See `docs/hardening-0.1.10.md` for decisions and remaining scaling limits.
* **0.1.9 released:** run `39269460274` passed: 102/102 jars, 62/62 GitHub
  Releases, 102/102 Maven publications, both backend images. Independent local
  Maven reads returned HTTP 401, so no separate byte-verification claim is made.
  Superseded in production by the 0.1.10 deployment below.

* **Input/microphone/performance follow-up (released in 0.1.9):** 26.3 text fields
  explicitly own native SDL text input; runtime tests now check SDL activation,
  native KeyboardHandler text dispatch and blur/removal. Whisper independently
  opens proximity capture; groups have an independent PTT/voice-activation mode
  (open-mic default preserved). Control writes use a bounded connection FIFO;
  audio lifecycle waits moved off game ticks without overlapping generations.
  Tracking shares unchanged immutable entries, reporting scans are rate-gated,
  and rejected/idle speaker decoding work is avoided. Both backends use shared
  hub access for stable presence and revalidate changed diffs at commit. See
  `docs/input-and-performance.md` for tests, benchmarks and the 100k-user scaling
  target/remaining limits. CI `39264680302` passed all six jobs; 26.3 runtime
  `39264685676` passed Fabric and Forge. The final client suite passes 98/98
  against each backend, and Linux Rust passes 15 tests plus 40/40 conformance.
  The backend was deployed by user request on 2026-10-19 as a locally built
  Rust 0.1.5 image from `01d1e52`; no new registry/client release was published.
  See `docs/deployment-2026-10-19.md` for the exact artifact and rollback.

* **Group roster and player settings (released as v0.1.8):** enabled by
  default via `showGroupHud`; right-edge member heads, white speaking outline
  and styled name, independent dragging/head size/background in Edit HUD.
  Player settings share one snapshot/page for names and controls, search voice
  users, and use confirmed cloud/group/SVC presence instead of tracked entities
  or stale transport selections. SVC compatibility 20 `state`/`states` metadata
  was independently observed in real-server run `39251350550`. The 26.3 startup
  harness covers SDL roster controls/search and actual GUI-extractor head
  rendering. Main CI `39252773119` passed all six jobs; 26.3 runtime
  `39252773053` passed both loaders. Release `39253618498` passed: 102/102
  validated jars, 62/62 GitHub Releases, 102/102 Maven publications, images
  skipped. The published 26.3 GitHub asset digests match the runtime-tested jars
  byte for byte. See `docs/release-0.1.8.md`. The public backend is unchanged.

* **26.3 settings input follow-up:** `McScreen` now translates SDL buttons and
  navigation scancodes to the shared UI contract. Runtime coverage exercises
  actual settings widgets through Minecraft event objects, plus all mouse
  phases and text input. Runtime run `39138274562` passed both loaders and
  main CI `39138258546` passed all six jobs. Released as v0.1.7-mc26.3 through
  run `39139446951`: both jars and Maven packages published, images skipped.
  Verification run `39139963444` confirmed authenticated Maven downloads
  match the release jars; both jars also match the runtime-tested jars byte
  for byte. See `docs/release-0.1.7.md` and `docs/minecraft-26.3-input.md`.
* **Minecraft 26.3 startup fix (released as v0.1.6-mc26.3):** native unknown
  defaults plus migration of saved negative MCVoice keyboard values through
  vanilla `setKey` and Forge's separate `setKeyModifierAndCode`. Valid keys
  and Forge modifiers are preserved. Dedicated `mc-startup.yml` run
  `39135257384` passed Fabric 0.19.5 / API 0.163.0+26.3 and Forge 66.0.10 on
  Java 25: fresh main menu/world, saved-options Quick Play, mouse re-grab,
  canonical saved output, clean shutdown, and a vanilla negative control.
  Its separate test mod never enters production jars. mc-runtime-test 4.5.1
  has no 26.3 mod assets; its skipped general smoke jobs are not runtime passes.
  Release run `39135943359` passed: 2/2 jars and Maven publications, no images.
  Report recovery `39137283241` fixed the single-artifact layout report and
  verified authenticated Maven downloads against both published jars.
  See `docs/release-0.1.6.md`. This release is scoped to 26.3 and no backend images.
* **HUD editing (released in 0.1.8):** the shared Java 8 client includes the local
  speaker in the HUD during detected transmission. The microphone is hidden
  during idle push-to-talk, grey for silence in PTT/open-mic modes, and green
  while speaking. It defaults to 16 px at bottom right. General settings has
  **Edit HUD**: independent dragging, vertical/horizontal speaker rows,
  background toggle, 10–32 px microphone sizing, Done/Cancel/Reset. Layout is
  saved in `hudLayout` in `config/mcvoice.json`, using normalized positions.
  This changes shared client code; the published 0.1.4 jars remain unchanged.
* **Passing:** 62 Minecraft versions, 102 jars (`versions/build-status.json`).
  * Every Forge release 1.8–1.12.2, 1.13.2 and 1.14.4–26.3.
  * Fabric wherever Fabric API exists for 1.14.4–26.3.
  * Legacy Fabric 1.8–1.8.9, 1.9.4, 1.10.2, 1.11.2, 1.12.2 (1.8.1–1.8.8
    without Legacy Fabric API).
* **Earlier all-version stable release:** v0.1.4 (run 36308643678: 102/102 jars, 62/62 GitHub
  Releases, 102/102 Maven packages, images `0.1.4`/`latest`; all 134 jobs
  passed). Includes the 0.1.3-rc.1 features, optional cosmetic name-tag
  injections from issue #5, and modern Forge resource-pack metadata. Source
  `d337b49` is in `main`; runtime smoke passed six representative targets.
  See `docs/release-0.1.4.md`. Rust `0.1.4` was deployed on the public backend;
  it was superseded by the validated local 0.1.5 build on 2026-10-19.
* **Previous candidate:** v0.1.3-rc.1 (run 36277389029: 102/102 jars,
  62/62 GitHub Releases, 102/102 Maven packages, both backend images). Adds
  compact shaded speaking indicators and formatted HUD names across every
  supported adapter, 20-entry group pages, and per-connection group budgets.
  The owner tested the rendering changes in Fabric 26.1.2; the full matrix
  was built and validated in CI. See `docs/release-0.1.3-rc.1.md`.
* **Earlier stable release:** v0.1.2 (run 36250351566: 102/102 jars, 62/62 GitHub
  Releases, 102/102 Maven packages, images `0.1.2`/`latest`) adds voice
  groups, the macOS microphone fix and vanilla-style screens. The public
  backend uses protocol 1.1 and advertises `groups`; see the
  deployment below for its current image.
* **Earlier release v0.1.0:** every passing version has an `mc/` branch
  and a `v0.1.0-mc<version>` GitHub Release (62 releases). Full run
  36112725820 (`release-report.md` on `v0.1.0`), then 36115472419 for
  1.8.1–1.8.8 Legacy Fabric, 1.13.2 and a retry of Forge 1.21.11
  (`release-report-run36115472419.md`). Maven: every jar is in GitHub
  Packages; re-runs record `exists`. Backend images `0.1.0`/`latest` pushed.
* **Public backend:** `wss://mcvoice.ravoxx.dev/v1/control` (Rust 0.1.11,
  source `45b0962`, image selected by `MCVOICE_VERSION=0.1.11` in
  `/opt/mcvoice/.env` on 5.83.145.152). Published digest
  `sha256:ae72f8cd7efae7a4b28a2682f887c1f20bf4c45f5bb2fa90b6c08d7bdd96af70`.
  Deployed 2026-10-23 14:08:14 UTC after isolated staging and image verification.
  Exact gateway `172.24.0.1/32` remains trusted; Mojang auth and ports preserved.
  Local/public readiness and protocol 1.1 (`groups`, `group_paging`,
  `group_directory`) pass; all 30 unrelated container IDs unchanged.
  The published 0.1.10 image and mode-600 environment backup remain for rollback:
  `sh /opt/mcvoice/candidates/0.1.11/rollback.sh`. See
  `docs/deployment-0.1.11.md` for digests and checks. Earlier deployment details
  remain in `docs/deployment-0.1.10.md` and `docs/deployment-2026-10-19.md`.
  Host nginx vhost `mcvoice.ravoxx.dev` → `127.0.0.1:18455`, certbot
  `dns-cloudflare` certificate, DNS not proxied. UDP 24455 is opened in the
  host's `vpn_hardening` nftables firewall via
  `/etc/vpn-hardening/firewall-base.nft` (input + forward). The host is a shared
  production server (mail, other sites): only ever add, validate (`nginx -t`,
  `nft --check`), never restart others. Since 0.1.1 the jars default to this
  backend (`mcvoiceBackendUrl` in `client/gradle.properties`). Measurements and
  limits remain in `docs/rust-routing-performance.md` and
  `docs/hardening-0.1.10.md`; no general latency or 100k-capacity claim is made.
* **SVC interop:** verified against SVC 2.6.24 on Paper 1.18.2, 1.19.4,
  1.20.1 and 1.21.4 (compatibility 20, AES-GCM with 12-byte IV). Older
  compatibility versions (19–16) are not verified.
* **Client runtime smoke test:** the owner approved it for 0.1.4. Run
  `36308348990` passed all six targets: Fabric and Forge on 1.16.5, 1.21.1,
  and 26.1.2. The tested revision `5948dc7` has the same tree as merged
  `main` revision `d337b49`. This checks initialisation, world entry, clean
  exit and MCVoice runtime errors, not visual quality or real voice delivery.
  Initial runs exposed missing modern Forge `pack.mcmeta` and two harness
  issues: older Fabric API Maven jars are metadata-only (use the bundled
  GitHub release), and Forge's caught optional-mod lookup at TRACE is not a
  runtime error. The severity-aware log checker has regression tests and
  still fails MCVoice runtime stacks, error records and HUD failures.
* **Not implemented** (reasons are in `versions/supported.md`):
  * Forge 1.14.2/1.14.3 (MCP names, 1.14 class names: extend `mcp13`);
  * Legacy Fabric 1.13.2 (no API; needs a Legacy Yarn 1.13 adapter).
* **Voice groups:** implemented in both backends (conformance 41/41) and the
  client (end-to-end tests against both backends). Version 0.1.3-rc.1 adds
  negotiated paging, scroll/search loading, stale-response protection and
  per-connection request limits. Legacy clients/backends retain their
  compatible list behavior.
* **Ideas / next steps:**
  * expand runtime smoke coverage; add Legacy Fabric when mc-runtime-test supports it;
  * group voice for SVC interop;
  * receiving SVC voice from a second real client in CI;
  * shared-state backend scaling (Redis).
