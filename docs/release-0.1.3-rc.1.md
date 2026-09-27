# MCVoice 0.1.3-rc.1

[Release and downloads](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.3-rc.1)
· [Full release workflow](https://github.com/RavoxX/MCVoice/actions/runs/36277389029)
· [Release report](https://github.com/RavoxX/MCVoice/releases/download/v0.1.3-rc.1/release-report.md)

This candidate includes the small shaded green microphone beside visible
speaking players' name tags, server-formatted names in the speaker HUD,
20-entry group pages loaded on scrolling/search, and per-connection group
request budgets. All supported adapters include these features.

The single release workflow built and validated 102 JARs for 62 Minecraft
versions, published 62 per-version GitHub releases and 102 Maven packages,
and published both backend images as `0.1.3-rc.1`. All 134 jobs passed,
including protocol vectors, Rust/Go tests, conformance, load smoke, client
core/e2e tests and container smoke tests. The source revision is
`53e21a1272810cc6a29e76b8d7dca90bd32d9de8`; each `mc/` branch also contains
its generated build. Checksums accompany each per-version release.

The owner tested the 26.1.2 Fabric rendering changes in game. Other clients
were compile/production-JAR validated; `mc-smoke.yml` was not run, following
the owner's preference to test the JAR directly.

## Public backend deployment

Deployed on 2026-09-27 (Europe/Berlin) to `5.83.145.152`:

- Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.3-rc.1`.
- Registry digest: `sha256:565c297337e8972e79c1b45bf17b69f754c7adb2fcef08984109dde381f8d918`.
- Running image revision matches the release source above.
- Local readiness and public HTTPS `/health` and `/ready` passed.
- Public WebSocket `hello_ok` confirmed protocol 1.1 with `groups` and
  `group_paging` capabilities.
- The container had zero restarts and no error/panic log entries during the
  immediate post-deployment check. Other running container IDs and start
  times were unchanged.

Only `MCVOICE_VERSION` in `/opt/mcvoice/.env` was changed; a mode-600 backup
was retained and `/opt/mcvoice/update.sh` recreated the MCVoice backend.
Rollback uses the previous tag `sha-948bd584df18`. No nginx, firewall, or
other service changes were needed.
