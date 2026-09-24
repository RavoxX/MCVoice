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
   world, in range. `PlaybackValidator` is checked on every frame of every
   transport. Never rely on server address, dimension name or coordinates
   alone (proxy sub-servers share all three). See `docs/proximity-security.md`.
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
# conformance (30 scenarios) against either backend binary
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

**Legacy Forge (1.8–1.12.x)**
* 1.8.9–1.12.1 build with Essential's architectury-loom; 1.8 and 1.8.8 with
  Unimined 1.4.1.
* 1.12.2 builds with RetroFuturaGradle 2.0.4 (no userdev jar exists; RFG 2
  needs a Java 25 Gradle JVM).
* MCP renames: `getConnection` from 1.9; `player`/`world`/`fontRenderer`
  from 1.10. The font is read via `ingameGUI.getFontRenderer()`.

**Legacy Fabric**
* Legacy Fabric API exists only for 1.8, 1.8.9, 1.9.4, 1.10.2, 1.11.2 and
  1.12.2.
* Its API classes live in the `-common` module artifacts.
* It has no HUD callback, so the HUD is a mixin on `InGameHud#render`.

**Mojang family input**
* Keys use `InputConstants.KEY_*` from 1.20 on (26.3 no longer has LWJGL's
  GLFW on the compile classpath) and `GLFW.GLFW_KEY_*` below.

**Validation**
* `validate_jar.py` prints the platform classes of each jar. Every total is
  about 327 classes, because Java 8 targets add synthetic classes where newer
  targets use nestmates. The platform list is what matters.

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

* **Passing:** 50 Minecraft versions, 87 jars (plus 1.14.4–1.15.2 built on main at the end of session 1, but not yet in `build-status.json`, and without `mc/` branches).
  * Every Forge release 1.8–1.12.2 and 1.16.1–26.3.
  * Fabric wherever Fabric API exists for 1.16.5–26.3.
  * Legacy Fabric wherever Legacy Fabric API exists.
* **SVC interop:** verified against SVC 2.6.24 on Paper 1.18.2, 1.19.4,
  1.20.1 and 1.21.4 (compatibility 20, AES-GCM with 12-byte IV). Older
  compatibility versions (19–16) are not verified.
* **Not implemented** (reasons are in `versions/supported.md`):
  * 1.13.2 (Forge MCP-1.13 era);
  * Legacy Fabric versions without Legacy Fabric API.
* **Ideas / next steps:**
  * client runtime smoke test (headless client launch / Fabric gametest);
  * group voice for SVC interop;
  * receiving SVC voice from a second real client in CI;
  * shared-state backend scaling (Redis);
  * the 1.13 gap;
  * check release run https://github.com/RavoxX/MCVoice/actions/runs/36054904091 (v0.1.0).
