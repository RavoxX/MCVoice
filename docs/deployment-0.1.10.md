# Rust 0.1.10 production deployment

Superseded in production by [Rust 0.1.11 on 2026-10-23](deployment-0.1.11.md).
The 0.1.10 image is retained for rollback.

Deployed on **2026-10-19 at 16:36:35 UTC**, after the user authorized the new
release and backend update. Only MCVoice on the existing shared host was
recreated. All **30 other running containers** retained their identities.

* Source: `c10a847774d458d6c7c9913852bd49f92c1c1ce0`.
* Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.10`.
* Published digest:
  `sha256:7e85c1c5f40484a71314db82a20a0e817938e8a1ed316903486054f6c955fe4f`.
* Running image id:
  `sha256:394f272021ea663f3932ff7963261c1908143b535878f25e13d4a3ca7d2e4c78`.
* Release/image workflow:
  [39285852532](https://github.com/RavoxX/MCVoice/actions/runs/39285852532).
  Both image publications completed; the client release matrix was still
  running at deployment time.

The workflow builds and smoke-tests the image in a read-only job, then loads
and pushes that same image in an isolated publisher. Before production, a
separate container used the production environment and loopback test ports.
Its source/version labels, numeric `65532:65532` user, Mojang auth, readiness,
build metric and protocol 1.1 hello with `groups`/`group_paging` all passed.
The temporary container was removed after deployment.

Production checks passed for local and public HTTPS readiness and TLS-verified
WebSocket hello, reporting Rust 0.1.10 and the expected capabilities. The exact
port-binding JSON before/after matches: control remains loopback
`127.0.0.1:18455 → 8080`, voice remains UDP `24455 → 24455`.
`AUTH_MODE=mojang`, public hostname and existing auth settings are preserved.
The container remains non-root, read-only, with all capabilities dropped and
no-new-privileges. No restart/error/panic was observed during verification.
An older client authenticated after the restart; this is not a claim of
human-to-human audio testing on production.

The environment now selects `MCVOICE_VERSION=0.1.10` and explicitly trusts only
the actual Docker gateway, `TRUSTED_PROXY_CIDRS=172.24.0.1/32`. Host nginx already
overwrites XFF with its peer address, so nginx, TLS, DNS and firewall settings
needed no changes. The obsolete local-image override was archived, allowing
future versioned updates to use the normal Compose image selection. No other
services or global Docker image pruning were invoked.

## Rollback

Original `.env`, Compose, local-image override and update script are retained
under `/opt/mcvoice/candidates/0.1.10/rollback/` (directory 700, `.env` 600).
Rollback restores the original environment and override, recreates only the
backend with `--pull never`, and checks readiness:

```sh
sh /opt/mcvoice/candidates/0.1.10/rollback.sh
```

Retained previous image:
`ghcr.io/ravoxx/mcvoice/voice-backend-rust:local-01d1e52`, id
`sha256:034dd95b8741c2a5cfaeae2d42e7364c30e3478564fecff8d49133964f2e9367`.
No secrets or environment contents are committed. Measurements and remaining
capacity/security limits are in [the hardening report](hardening-0.1.10.md).
This deployment does not prove 100k-user capacity or live Kubernetes operation.
