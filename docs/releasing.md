# Releasing

Releases are made by `.github/workflows/release.yml` (Actions → release →
Run workflow). Nothing is built or uploaded from a developer machine.

## Versioning

* The mod and backend share one semantic version (`client/gradle.properties`
  `mod_version`; backend `--version`).
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
3. Bump `mod_version` if needed, and set the repository variable
   `MCVOICE_BACKEND_URL` if release jars should ship with a default backend.

## What the workflow does

| Job | Does | Fails / records when |
|---|---|---|
| `plan` | Picks the versions: `supported` = all loaders pass in `build-status.json`, or an explicit list; checks that each `mc/<version>` branch exists | a branch is missing |
| `test` | Full `ci.yml`: vectors, both backends (lint, unit, fuzz), conformance for both, load smoke, client core and end-to-end tests against both backends, secret scan | any failure blocks the builds |
| `build` | `mc-build.yml` on each `mc/<version>` branch with `-Pmod_version=<version>`: build, validate jar, `gradle publish` to GitHub Packages | per loader: `status`, `reason`, `maven` (`published` or `failed: <exact error>`) |
| `github-release` | Per version: `checksums-sha256.txt`, creates or updates release `v<version>-mc<minecraft>` with the jars | skipped unless **every** loader of that version built; the `gh` error text is recorded on failure |
| `images` | `backend.yml` with the version: build, container smoke test, push to GHCR | job result |
| `report` | `tools/release/report.py` → `release-report.md` (job summary, artifact, attached to `v<version>`) | always runs |

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
GitHub Packages rejects re-publishing the same version, which the report
shows as `failed: … 409`. Bump the version instead.
