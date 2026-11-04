# Rust 0.1.13 deployment verification

Rust 0.1.13 is deployed on the public MCVoice backend following the owner's
request. Release validation and tested-image publication passed before the
production switch at `2026-10-24T18:55:57.603059582Z`.

* Source: `39acc90c54d4d75b6febe4cb5b18480fc470872a` (merged PR #22).
* Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.13`.
* Published digest: `sha256:f8e7d5c47690c96b6a6e14b9a5aaa2e9ce4751c07ab3c5f3a84a53458ed5f3c1`.
* [Release workflow 40122108949](https://github.com/RavoxX/MCVoice/actions/runs/40122108949).

An isolated candidate passed exact image/source, readiness, metrics and protocol
checks. Only MCVoice was switched. Authentication, proxy trust and ports were
preserved; all 30 unrelated container identities were unchanged. The previous
0.1.12 image and protected configuration backup remain available for rollback.
Detailed operational instructions stay local.

Local/public readiness and protocol 1.1 negotiation pass, including `groups`,
`group_paging`, `group_directory` and `group_modes`. An independent workstation
check passes. A server recheck roughly 25 minutes later found no error/panic,
no restart and no unrelated-container changes. The candidate was removed and the
SSH session closed.

These probes do not perform a new live Mojang account login or human microphone
test. Shared 43-scenario conformance and client end-to-end tests against both
backends cover routing and group behavior. See [release evidence](release-0.1.13.md)
and [security boundaries](security-0.1.13.md).
