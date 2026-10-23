# MCVoice 0.1.10

Stable release [39285852532](https://github.com/RavoxX/MCVoice/actions/runs/39285852532)
uses source `c10a847774d458d6c7c9913852bd49f92c1c1ce0` and all 62 supported
Minecraft versions. All **260 jobs passed**: **102/102 validated jars**,
**62/62 GitHub Releases**, **102/102 Maven packages**, and both backend images
(`0.1.10`/`latest`).

[Verification evidence](release-0.1.10-verification.json) matches every GitHub
jar digest to the Maven jar downloaded and byte-compared by the isolated
publisher. Both 26.3 release jars also match the runtime-tested jars byte for
byte. The genuine Windows local builds remain separate: their 26.3 class files
match CI exactly; package differences are text line endings and Fabric's
Gradle-version manifest field (local 9.8.1, CI 9.8.0).

## Changes

Replay/key rotation, trusted proxy identity, reconnect password budgets and
atomic moderation/admission are hardened. Client configuration is safe across
UI/audio/network threads; group decoding survives positional transport switches
and current membership remains mandatory. Empty EOS, WebSocket control replies,
capture interruption, Realms, mic-test lifecycle and protocol edge cases are
corrected. Both relays encrypt fanout outside recipient locks and avoid discarded
presence allocations. CI separates third-party execution from writable
publication credentials and verifies official test-server download digests.

The real SVC probe caught a legitimate 256-byte GCM authentication ACK that the
initial strict check rejected. The final implementation supports this observed
authenticated form while preserving rejection of malformed ACKs, padded CBC and
tampered GCM. No existing test assertions were weakened.

The release also contains 0.1.9's group HUD/settings, player search/voice-user
filtering, 26.3 native text input, independent group push-to-talk, whisper-key
transmission and game-thread/network/audio lifecycle improvements.

## Validation and limits

Final local client suites pass 112 tests against each backend, zero skipped or
failing. Final-source CI
[39284820030](https://github.com/RavoxX/MCVoice/actions/runs/39284820030),
26.3 Fabric/Forge runtime
[39284987176](https://github.com/RavoxX/MCVoice/actions/runs/39284987176), and
real SVC 1.21.4
[39284820339](https://github.com/RavoxX/MCVoice/actions/runs/39284820339)
are all completed successfully. The release performs its own complete CI gate and
per-loader validation. All version branches were regenerated and checked to
contain the final source before dispatch.

[The hardening report](hardening-0.1.10.md) documents all findings, tradeoffs,
regressions and before/after data. Changing Go presence at 1,000 peers reduces
median sweep allocations from 1.67 to 1.18MB and time from 5.29 to 3.83ms.
No general relay/mixing speedup is established; dense Go forwarding remains
saturated. 100k concurrent users remains an unproven scaling target.

## Production

Rust 0.1.10 was [deployed at 16:36:35 UTC](deployment-0.1.10.md), after isolated
staging and the tested image publication. Readiness, public TLS/WebSocket,
Mojang auth, exact ports/proxy settings and image provenance passed. All 30
unrelated containers were unchanged; the previous image/configuration remain
available for rollback.

Four genuine local 26.2/26.3 Fabric/Forge builds include the final SVC correction
and pass jar/version/metadata validation. Their checksums/provenance are in
`release-output/local-jars-0.1.10/`, separate from CI publication artifacts.
