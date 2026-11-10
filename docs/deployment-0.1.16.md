# Rust 0.1.16 deployment verification

Rust 0.1.16 is deployed on the public MCVoice backend by the owner's request.
The switch occurred at `2026-11-10T20:48:29.632445209Z`, after the release
workflow completed and an isolated candidate passed its checks.

* Source: `bf197b4923dd2b51e48b7318c2774feb59aacf24` (merged PR #27).
* Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.16`.
* Published digest: `sha256:9aba27d37a98d5849001e9fa529b217119aed69496788c46c606c001d01ee362`.
* [Release workflow](https://github.com/RavoxX/MCVoice/actions/runs/42896428425).

The candidate passed exact source/image/version, readiness, metrics and
protocol checks, and started without warnings. Only MCVoice was switched.
Mojang authentication, exact proxy trust and published ports were preserved;
all **33 unrelated container identities** are unchanged. Production has no
restart and no warning, error or panic in its log after the switch. The
candidate was removed after verification.

Local/public readiness and protocol 1.1 negotiation pass, including `opus_fec`,
`groups`, `group_paging`, `group_directory` and `group_modes`. An independent
workstation check passes. The one player connected before the switch was
connected again with a voice session seconds after it, with no authentication
failure counted. These probes do not perform a new live Mojang account login
or a human microphone test.

The previous production **0.1.15** image and protected configuration backup
remain available for rollback. Detailed operational reports and connection
details stay local. See [release evidence](release-0.1.16.md) and the
[list of fixes](rust-backend-review-0.1.16.md).
