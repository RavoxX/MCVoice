# Backend deployment — 19 October 2026

Historical 14:02 deployment, superseded by
[Rust 0.1.10 at 16:36 UTC](deployment-0.1.10.md). The artifact and rollback
details below describe the earlier local 0.1.5 installation.

The user authorized replacing the existing MCVoice backend on 5.83.145.152.
Only MCVoice was restarted. No image, Maven package or client release was
published. Client changes remain on `codex/input-group-ptt-performance`.

## Running artifact

* Started: **2026-10-19 14:02:57 UTC**.
* Rust backend version: **0.1.5**, source
  `01d1e52be3b9acd8f8bb38198cae664765d901e7` (backend unchanged in `cbf430c`).
* Local image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:local-01d1e52`.
* Image id: `sha256:034dd95b8741c2a5cfaeae2d42e7364c30e3478564fecff8d49133964f2e9367`.
* Linux binary SHA-256:
  `be5b2068513e32d40d46021e48f2c09d9686280545ef34d52a4a4fbde13addb1`.
* Source archive SHA-256:
  `cf7f15660bd63f4bc2c1c9dc8947d500ee8ec70702cd33872a6bb1545d2fc002`.

The candidate was built on the existing Debian 12 host with Rust 1.94.0,
locked dependencies, two build jobs and reduced scheduling priority, in
`/opt/mcvoice/candidates/01d1e52`. The final image replaces the binary in the
previous 0.1.4 distroless image, retaining its non-root runtime and adding the
exact source/version labels. The existing production image is retained.

## Validation and service state

All 15 Rust tests and Clippy with `-D warnings` pass on Linux. The real Linux
binary passed **40/40** black-box conformance scenarios, including routing,
groups, replay/authentication and Mojang success/failure/resume tests. The first
candidate test harness lacked the vector-directory environment variable; it was
corrected and the complete vector suite rerun successfully.

A separate container first started with the production environment, bound only
to loopback test ports. Its readiness and WebSocket hello returned protocol 1.1,
`groups` and `group_paging`, with Mojang authentication configured. That staging
container was removed after the successful switch.

The production container is running without restarts. Local readiness, public
HTTPS readiness and TLS-verified WebSocket hello were verified from the server
and independently from the developer machine. They report Rust 0.1.5 and the
same capabilities. The environment file is byte-for-byte unchanged; the
authentication/port settings before and after match, including `AUTH_MODE=mojang`,
loopback TCP `127.0.0.1:18455 → 8080` and UDP `24455 → 24455`. The runtime remains
non-root, read-only, with all capabilities dropped and no-new-privileges.
All **30 unrelated running containers** retained their identities. Nginx,
certificates, DNS and firewall configuration were unchanged. No ERROR/panic
records were found in the new backend's logs during verification.

Source validation: [CI 39264680302](https://github.com/RavoxX/MCVoice/actions/runs/39264680302)
and [26.3 runtime 39264685676](https://github.com/RavoxX/MCVoice/actions/runs/39264685676).
Performance measurements and remaining limits are in
[the performance report](input-and-performance.md). Neither the deployment nor
these 200-client loopback tests establish 100k-user capacity.

## Rollback and future updates

The original compose file, update script and environment backup are under
`/opt/mcvoice/candidates/01d1e52/rollback/` (directory mode 700; environment mode
600). The previous image is
`ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.4`, id
`sha256:2419641872b708ba15eb5f9b5d907a8cbeda1c805dd381f279409fe17630d243`.

Rollback command on the server:

```sh
sh /opt/mcvoice/candidates/01d1e52/rollback.sh
```

It archives the new compose override and recreates only MCVoice from the
original compose file and unchanged environment, using the retained local
0.1.4 image. The script is prepared; a rollback was not needed or exercised.

The new image is pinned in `/opt/mcvoice/docker-compose.override.yml` with
`pull_policy: never`. It exists locally only; the original `.env` version pin
is unchanged. **Before a future registry release update**, archive/remove that
override, set `MCVOICE_VERSION` to the intended published version and run the
normal `/opt/mcvoice/update.sh`. Editing `MCVOICE_VERSION` alone cannot replace
the explicit override. The local image has not been pushed to GHCR.
