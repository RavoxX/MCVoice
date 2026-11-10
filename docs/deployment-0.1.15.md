# Rust 0.1.15 deployment verification

Rust 0.1.15 is deployed on the public MCVoice backend by the owner's request.
The switch occurred at `2026-11-04T20:11:36.072791612Z`, after isolated staging,
image verification and completed client release validation.

* Source: `66b9f7a5be11c5717b9f9450668882ddd164c72d` (merged PR #25).
* Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.15`.
* Published digest: `sha256:bd8ab7606e3fadf8a270baa2c23c02ae5298e94cc7c31d7f9440bb757ceba987`.
* [Release workflow](https://github.com/RavoxX/MCVoice/actions/runs/41891134610).

The isolated candidate passed exact source/image/version, readiness, metrics
and protocol checks. Only MCVoice was switched. Mojang authentication, exact
proxy trust and published ports were preserved; all **33 unrelated container
identities** are unchanged. Production has no restart or error/panic during the
deployment check. The candidate was removed after verification.

Local/public readiness and protocol 1.1 negotiation pass, including `opus_fec`,
`groups`, `group_paging`, `group_directory` and `group_modes`. An independent
workstation check passes. These probes do not perform a new live Mojang account
login or human microphone test. Shared 44-scenario conformance and client
end-to-end tests against both backends cover routing and group behavior.

The previous production **0.1.13** image and protected configuration backup
remain available for rollback. The interrupted 0.1.14 candidate was never
deployed. Detailed operational reports and connection details stay local.
See [release evidence](release-0.1.15.md) and
[audio scope, compatibility and remaining limits](audio-reliability-0.1.15.md).
