# Rust 0.1.11 production deployment

Deployed on **2026-10-23 at 14:08:14 UTC** (16:08:14 Europe/Berlin), following
the owner's request to release 0.1.11 and deploy the backend. The six release
CI gates and both tested-image publications had passed; the Minecraft release
matrix was still running at deployment time.

* Source: `45b096230a20dc0baeefc96231d609f454c9bd68` (merged PR #13).
* Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.11`.
* Published immutable digest:
  `sha256:ae72f8cd7efae7a4b28a2682f887c1f20bf4c45f5bb2fa90b6c08d7bdd96af70`.
* Running image id:
  `sha256:1a6f14025cc81c09e01988e0ea77827dc31fd5893fff40de48d4be0a4a5314d8`.
* Release/image workflow:
  [39965856784](https://github.com/RavoxX/MCVoice/actions/runs/39965856784).

The published digest was matched to the isolated publisher's Docker push
output. A separate candidate container used the production environment and
Mojang authentication, the existing private Docker network, read-only storage,
numeric user `65532:65532`, dropped capabilities and no-new-privileges. Its
temporary control/UDP ports were bound only to loopback. Source/version labels,
readiness, metrics and protocol 1.1 with `group_directory` passed before the
production change. The candidate was removed afterwards.

Only `MCVOICE_VERSION` in `/opt/mcvoice/.env` changed. Existing configuration
bytes were checked against the backup before replacement, and only the backend
service was recreated with `--no-deps --pull never`. No image pruning, nginx,
TLS, DNS, firewall or unrelated service changes were performed.

Verification passed locally, over public HTTPS/TLS and a WebSocket hello, then
again independently from the Windows workstation. The server reports Rust
0.1.11 with `groups`, `group_paging` and `group_directory`. Exact Docker port
bindings remain `127.0.0.1:18455 → 8080` and UDP `24455 → 24455`.
`AUTH_MODE=mojang`, public hostname and exact trusted gateway `172.24.0.1/32`
are preserved. Restart count was zero and no backend error/panic was observed.
All **30 unrelated containers** retained their exact IDs. This is not a claim
of human-to-human audio or new live Mojang account authentication testing.

## Rollback

The previous `.env`, Compose file and update script remain in
`/opt/mcvoice/candidates/0.1.11/rollback/`. Candidate/rollback directories are
mode 700; the environment and its backup are mode 600. The mode-700 rollback
script restores the original environment, recreates only this service using
the retained image, and verifies readiness/ports and unrelated container IDs:

```sh
sh /opt/mcvoice/candidates/0.1.11/rollback.sh
```

Retained rollback image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.10`,
id `sha256:394f272021ea663f3932ff7963261c1908143b535878f25e13d4a3ca7d2e4c78`,
published digest
`sha256:7e85c1c5f40484a71314db82a20a0e817938e8a1ed316903486054f6c955fe4f`.

No `.env` file or credentials were downloaded or committed. Only
safe verification metadata was retained locally; the SSH session was closed.
Group directory behavior, fallback skins, compatibility and capacity limits
are documented in [the feature report](group-directory-and-updates.md).
