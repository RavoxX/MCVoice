# Releasing

Releases are made by `.github/workflows/release.yml` (Actions → release →
Run workflow). Published release artifacts are built and uploaded by CI.
Local test builds are separate evidence and do not replace CI validation.

## Versioning

* A release uses a semantic version (`client/gradle.properties` `mod_version`;
  backend `--version` when included). A client-only release increments the mod
  version with `images: false`; the backend retains its previous version.
* Jar: `mcvoice-<version>-mc<minecraft>-<loader>.jar`, for example
  `mcvoice-0.1.0-mc1.20.1-fabric.jar` or `mcvoice-0.1.0-mc1.8.9-legacyfabric.jar`.
* Git tag per Minecraft version: `v<version>-mc<minecraft>` (`v0.1.0-mc1.20.1`),
  pointing at the head of branch `mc/<minecraft>`.
* Maven (GitHub Packages): `io.github.ravoxx.mcvoice:voice-client-{fabric,forge,legacyfabric}:<version>+mc<minecraft>`.
* Images (GHCR): `ghcr.io/<owner>/<repo>/voice-backend-{rust,go}:<version>`.
  `latest` is added only for a stable (non-pre-release) release run from `main`.
* An umbrella release `v<version>` carries `release-report.md`.

## Before a release

1. `main` is green (`ci.yml`) and `versions/build-status.json` is current:
   run `mc-build` for changed versions, then `tools/versions/collect_status.sh`.
2. Create or refresh the version branches:
   `tools/port-version/make-branch.sh --all-supported --push`. Each
   `mc/<version>` branch is `main` plus the generated `minecraft/` build. It is
   refreshed by merging `main`, never by rewriting its history.
3. Bump `mod_version` if needed (GitHub Packages never replaces a published
   version). The default backend of the jars is `mcvoiceBackendUrl` in
   `client/gradle.properties`.

For runtime validation, dispatch `mc-smoke.yml` with representative Minecraft
versions after owner approval to run its third-party test code. It checks world
entry and clean shutdown as well as MCVoice's logs. Fabric API must come from
its bundled GitHub release: older Maven jars contain only metadata. The log
checker ignores loader TRACE/DEBUG diagnostics, while MCVoice runtime stack
traces, error records and HUD failures still fail the check.

For Minecraft 26.3, dispatch `mc-startup.yml` instead: mc-runtime-test 4.5.1
has no test mod for that version. The dedicated workflow tests both loaders
with fresh default keys, then reuses the generated world/configuration and
launches Quick Play with saved invalid MCVoice keys and valid custom binds.
See `docs/version-porting.md` for the checks and pinned dependencies. Its
test-only jar is never included in production or publication.

For Updates buttons and HUD corner resizing, dispatch `mc-ui.yml`, or set
`native_ui: true` in `mc-startup.yml`. This separate mode tests Fabric and Forge
on both 26.2 and 26.3 through OS mouse events, captures screenshots and records a
validated browser-launch request without opening a browser. It does not replace
the default 26.3 startup/world tests. Production jars must contain no test-mod
classes; compare their bytes with the eventual release assets.

## What the workflow does

| Job | Does | Fails / records when |
|---|---|---|
| `plan` | Picks the versions: `supported` = all loaders pass in `build-status.json`, or an explicit list; checks that each `mc/<version>` branch exists | a branch is missing |
| `test` | Full `ci.yml`: vectors, both backends (lint, unit, fuzz), conformance for both, load smoke, client core and end-to-end tests against both backends, secret scan | any failure blocks the builds |
| `build` | Read-only `mc-build.yml` builds/validates jars on `mc/<version>`. A separate publisher uploads prebuilt jars/sources with a minimal Maven-only build and verifies downloaded jar bytes | per loader: `status`, `reason`, `maven`, verified `maven_sha256`; missing publication results are explicit failures |
| `github-release` | Per version: `checksums-sha256.txt`, creates or updates release `v<version>-mc<minecraft>` with the jars | skipped unless **every** loader of that version built; the `gh` error text is recorded on failure |
| `images` | Read-only image build/container test, then a separate publisher loads and pushes the tested image without running it | job result |
| `report` | Selects newest status/result artifacts by creation time; renders and attaches `release-report.md` | fails if the planned version/loader inventory, GitHub publications, verified Maven read-back or required validation/image gates are incomplete |

The report lists only recorded facts. If GitHub refuses an action (for
example `packages: write` is not granted to the workflow token, or a tag
already exists and is protected), the exact error appears in the report. It
is never reported as success.

## Permissions

The workflow token needs `contents: write` (tags, releases), and
`packages: write` (Maven packages and GHCR images). Organisation or
repository settings can cap `GITHUB_TOKEN` at read-only ("Workflow
permissions"). In that case every publish step fails visibly, and the report
says which.

## Re-running

Releases are idempotent per tag: re-running with the same version replaces
the release assets (`--clobber`). Maven packages cannot be overwritten:
GitHub Packages rejects re-publishing the same version (409). The report
lists those packages as "already published earlier, not replaced": the
Maven artifact is the one from the first successful run, while the release
assets are the rebuilt jars. To publish new Maven artifacts, bump the version.

A run over selected versions (not `supported`) attaches its report as
`release-report-run<run id>.md`, so the full report of the last `supported` run stays.
Run it with `images: false` unless the backend changed.

For a completed, single-version stable client-only release whose report needs
recovery, dispatch `release-report.yml` with `run_id`, `version`, and
`minecraft`. It validates the original successful test/build jobs and recorded
publication results, checks release SHA-256s, and downloads both Maven jars
with `packages: read` to compare their bytes. It attaches only the report and
verification record through Actions; it does not rebuild or replace jars,
republish Maven versions, or build images. The report renderer supports both
flat single-artifact downloads and nested multi-artifact downloads.

For a full stable release from `main`, set `minecraft: supported` in
`release-report.yml`. This validates the supported plan at the original source
revision and all original test/build/Maven/diagnostic/image gates. It selects
the newest status/result artifact **by creation time** for each name across
reruns; artifact IDs and API order are not chronological. The completeness
check must pass before the recovered overview report is attached. This mode
does not rebuild or replace jars, republish Maven packages, or build images.
Independently verify actual release asset digests and runtime-tested jar identity
before deploying; a green workflow alone is not publication evidence.

## Backend-only deployments

When the client and protocol are unchanged, `backend.yml` also publishes
immutable `sha-<first 12 commit characters>` images from `main`. A backend-only
deployment can pin that tag after the matching `ci.yml` and image smoke tests
pass. This does not publish a new mod/Maven version or create a versioned
release; the binary's semantic version can still be the previous release.

For the public backend, retain the previous image tag and a mode-600 backup of
`/opt/mcvoice/.env`, change only `MCVOICE_VERSION`, then run its `update.sh`.
Verify the container's image revision, local `/ready`, external `/health`, and
a protocol 1.1 WebSocket `hello_ok` advertising `groups`. Roll back by restoring
the previous tag and rerunning the update. Do not change nginx, firewall rules,
or other containers for an image-only deployment.

The `report` job downloads only `mc-*-status` and `release-result-*` artifacts,
selecting the newest copy of each name by creation time. It reads only report
records, not jar payloads or build logs. A download failure fails the job;
retry the report or use the recovery workflow after the service recovers.
