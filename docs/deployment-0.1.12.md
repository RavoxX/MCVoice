# Rust 0.1.12 deployment verification

Rust 0.1.12 is deployed on the public MCVoice backend, following the owner's
request to release and deploy. The release's six validation gates and both
tested-image publications passed before switching production.

Public release artifacts:

* Source: `39d375f58d870adcf01777c9d2701138adf7e463` (merged PR #15).
* Image: `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.12`.
* Published image digest:
  `sha256:b9ffcd1352976765ae04f4c2bed2d14bcfcc65276b061aa7c02470413afcea83`.
* Workflow: [40012401858](https://github.com/RavoxX/MCVoice/actions/runs/40012401858).

An isolated candidate passed image/source, readiness, metrics and protocol
checks before production changed. Only MCVoice was switched. Authentication,
proxy trust and port settings were preserved; unrelated services were unchanged.
The previous 0.1.11 image and protected configuration backup remain available
for rollback. Detailed operational instructions are retained locally.

Local and public checks pass, including an independent workstation check and a
second server verification. The public service reports Rust 0.1.12 and protocol
1.1 with `groups`, `group_paging`, `group_directory` and `group_modes`. No backend
error/panic or restart was observed during verification. The candidate was
removed and the SSH session closed.

Group admission and audio routing are covered by the shared 43-scenario
conformance suite and client end-to-end tests against both backends. Deployment
probes check readiness and negotiation without a new live Mojang account login
or human microphone test. See [controls and compatibility](svc-style-controls.md)
and [release evidence](release-0.1.12.md).
