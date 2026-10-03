# MCVoice 0.1.4

[Release and downloads](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.4)
· [Full release workflow](https://github.com/RavoxX/MCVoice/actions/runs/36308643678)
· [Release report](https://github.com/RavoxX/MCVoice/releases/download/v0.1.4/release-report.md)

This stable release fixes [issue #5](https://github.com/RavoxX/MCVoice/issues/5):
cosmetic name-tag injections on modern and legacy Fabric use `require = 0`.
A missing injection match therefore does not fail the required injection-count
check. Both mixin configurations and essential HUD/tick/lifecycle hooks remain
required. This does not promise compatibility with every invalid mixin or mod
combination.

It also includes the features introduced in 0.1.3-rc.1: compact shaded green
microphones beside speaking players' name tags, server-formatted speaker HUD
names, 20-entry group pages loaded on scrolling/search, and per-connection group
request budgets. Modern Forge now contains version-appropriate `pack.mcmeta`,
fixing the missing-resource warning found during runtime testing. The JAR
validator checks this metadata for the EventBus 7 adapter.

## Release validation

The single release workflow passed all 134 jobs. It built and validated
**102/102 JARs** for **62 Minecraft versions**, published **62/62 per-version
GitHub releases** and **102/102 Maven packages**, and published both backend
images. All releases are stable, including the umbrella `v0.1.4` release.
Every published JAR asset digest matches its accompanying SHA-256 checksum;
the public release report matches the workflow artifact.
The checks included protocol vectors, Rust/Go tests, conformance, load smoke,
client core/e2e tests, secret scanning and both container smoke tests.

Release source on `main`: `d337b49a68b80c4c39462e94153187ecabded59e`.
The generated `mc/` branches contain this source plus their Minecraft builds;
release tags retain those exact revisions. Rust and Go backend versions, the
client version, and the published image tags are all `0.1.4`.

## Runtime smoke tests

[Run 36308348990](https://github.com/RavoxX/MCVoice/actions/runs/36308348990)
passed all six targets:

| Minecraft | Fabric | Forge |
|---|---|---|
| 1.16.5 | PASS | PASS |
| 1.21.1 | PASS | PASS |
| 26.1.2 | PASS | PASS |

Each real client initialized MCVoice, entered a world, exited cleanly and passed
the MCVoice runtime-log checks. The tested source `5948dc7` is tree-identical to
the merged release source `d337b49`. These are startup/world-handling checks;
they do not verify live voice, visual quality or arbitrary third-party mod
combinations. The owner previously checked the rendering in Fabric 26.1.2.

Initial runs found missing modern Forge resource metadata and two harness
problems: the old Fabric API Maven artifact lacked runtime modules, and caught
optional-mod lookups at TRACE were mistaken for runtime errors. The harness now
uses the bundled Fabric API release and a severity-aware log checker with seven
regression tests. Genuine MCVoice runtime stacks, error records, HUD failures
and missing initialization still fail the check.

## Backend images and deployment

Both images passed their container smoke tests and were published as `0.1.4`
and `latest`, with OCI revision labels matching the release source:

- Rust: `sha256:f4573f069d5179d9ff6de104e87012dce63e8c07e293fcb1d86617ec2e5deeeb`.
- Go: `sha256:2afb2211eee3916d137c71bb3eb8f88815ce321e8bfb5415ac88c38c657e736c`.

Deployed Rust `0.1.4` to `5.83.145.152` on 2026-09-27 at 10:01 UTC after
SSH access returned. The running image digest and revision match the release
above. Only `MCVOICE_VERSION` in `/opt/mcvoice/.env` was changed; a mode-600
backup was retained and `/opt/mcvoice/update.sh` recreated the MCVoice container.

Post-deployment checks passed:

- Local readiness and public HTTPS `/health` and `/ready` returned success.
- Public WebSocket `hello_ok` reported Rust `0.1.4`, protocol 1.1, `groups`
  and `group_paging`; authentication remains `mojang`.
- The container had zero restarts and no error/panic entries in its startup
  logs during the immediate check.
- Control remains bound to `127.0.0.1:18455` and voice to UDP `24455`.
- IDs and start times of the other 30 running containers were unchanged.

Rollback uses the previous image tag `0.1.3-rc.1`. No nginx, firewall or other
service changes were needed. Earlier SSH/HTTPS timeouts delayed the initial
attempt; that attempt made no production changes.
