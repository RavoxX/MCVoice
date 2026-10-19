# MCVoice 0.1.9

Stable release preparation for all 62 supported Minecraft versions (102 client
jars), plus the Rust and Go backend images. Publication is pending; exact
workflow results will be recorded after the release completes.

## Changes

* Minecraft 26.3 text fields explicitly start/stop native SDL text input when
  focused or removed. Earlier versions retain their existing input path.
* Hold the whisper binding (B by default) to transmit nearby without holding V.
* Groups have an independent Group Mic setting for voice activation or
  push-to-talk. Voice activation remains the default for old configurations.
* Control writes use a bounded FIFO per connection. Audio device close/join
  work moves off game ticks, with coalesced restarts and no overlapping owners.
* Tracking shares unchanged immutable state while checking local entities every
  tick; peer-report scans are rate-gated before allocation/traversal.
* The mixer avoids idle/rejected decode work, immediately silences rejected
  playback, and preserves group/whisper mode during loss concealment.
* Both backends reduce exclusive presence locking, revalidate changes before
  commit, and serialize/send outside the hub lock. Go avoids stable-presence
  allocation and busy UDP locks during jitter diagnostics.

Java 8 compatibility, privacy and every routing/playback security check remain
in place. See [the measurements and scaling plan](input-and-performance.md):
100k users remains an unproven target. Dense Go forwarding remains saturated in
the recorded local test; the measurements do not establish a general relay
latency improvement or a general audible-speaker decoding speedup.

## Validation before release

The final shared client suite passes 98/98 against each backend. CI
[39264680302](https://github.com/RavoxX/MCVoice/actions/runs/39264680302) passed
all six jobs. Minecraft 26.3 runtime
[39264685676](https://github.com/RavoxX/MCVoice/actions/runs/39264685676) passed
Fabric and Forge, including native SDL activation and KeyboardHandler text
dispatch. These builds used candidate version `0.1.9-test` before the release
version bump; the release builds perform their own tests and jar validation.

The first main secret scan flagged six source-file SHA-256 checksums in the
benchmark manifest. Each was reproduced from its referenced source (accounting
for checkout line endings). The manifest now uses separate `source`/`sha256`
fields; six exact immutable historical finding fingerprints are documented in
`.gitleaksignore`. There is no file/rule exclusion, scan bypass or history rewrite.
Gitleaks 8.24.3 reports no findings for both the historical release range and the
new manifest locally; CI must pass before publication.

The previously user-authorized public-backend deployment is the local Rust
0.1.5 artifact documented in [the deployment record](deployment-2026-10-19.md).
Publishing 0.1.9 images does not automatically replace that pinned service.
