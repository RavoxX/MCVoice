# MCVoice 0.1.15

Stable [release 41891134610](https://github.com/RavoxX/MCVoice/actions/runs/41891134610)
uses merged source `66b9f7a5be11c5717b9f9450668882ddd164c72d`
([PR #24](https://github.com/RavoxX/MCVoice/pull/24),
[PR #25](https://github.com/RavoxX/MCVoice/pull/25)):
**102/102 validated jars**, **62/62 Minecraft releases**, **102/102 byte-verified
Maven jars**, and both tested backend images (`0.1.15` / `latest`).

The [verification record](release-0.1.15-verification.json) matches every GitHub
jar digest to the jar downloaded and byte-compared by the isolated Maven
publisher. All 62 tags match the verified generated branch heads. Both 26.3
release jars equal the preserved runtime-tested production jars byte for byte.

## Selected improvements

Proximity Opus recovery uses redundancy from an adjacent packet that has already
arrived, with negotiated scope-safe metadata and current playback permission.
Every group delivery clears that metadata: asynchronous rosters have no
acknowledged membership generation. Groups and SVC retain loss concealment.
Encoder resets and stale-capture rejection protect world, group, route and
whisper boundaries. No extra waiting is added for recovery.

SVC retries failed UDP sessions automatically, moves DNS/socket setup off the
game thread, cancels stale attempts and prefers a speech break for fresh-key
renewal. Exact same-key nonce history and its hard bound remain mandatory.
Renewal can still pause SVC while the server expires its previous session;
seamless renewal is not claimed. Departed users are removed using the
independently observed `voicechat:remove_state` packet. Adaptive jitter estimates
now exclude talk breaks, duplicates and malformed inputs, and reset on path changes.

See [all seven proposal decisions, regressions and benchmark limits](audio-reliability-0.1.15.md).
Existing microphone processing and setup controls remain. New AEC, automatic
uplink bitrate selection and speculative backend restructuring are deferred.
The synthetic loss fixture reduced lost-frame MSE by about 81%; this is not a
perceptual quality or general CPU/latency claim. There is no proven 100k capacity.
Java 8, privacy, authentication and every positional/group playback check remain.

## Verification

[Merged-main CI](https://github.com/RavoxX/MCVoice/actions/runs/41889790792),
[PR CI](https://github.com/RavoxX/MCVoice/actions/runs/41888373535) and the release
gates pass: strict backend checks, reproducible vectors, **44/44 shared
conformance scenarios against each backend**, load smoke, Java 8 bytecode,
secret scanning and **145 client tests against each backend**, without failures
or skipped tests. The returning-member conformance case confirms group FEC
metadata is stripped even for capable receivers.

The initial 1.21.11 Forge build failed recompiling generated Minecraft sources
before MCVoice compilation. A fresh
[diagnostic build](https://github.com/RavoxX/MCVoice/actions/runs/41896869053)
passed both loaders with the same Forge 61.2.4 and Mavenizer 0.5.21. The failed
release job was then rerun on a fresh runner; no source, test or limit was
changed to make that target pass.

One later 1.9 release-result upload hit GitHub `ECONNRESET` after its release
assets were published. That publication job and its summary were retried;
client builds and Maven artifacts did not need changes.

Independent final verification then caught four incomplete publication records
despite green job statuses: GitHub download resets on 1.8.7, 1.21.8 and 1.21.11,
and an upload HTTP 500 on 1.8.6. Only those publication jobs and their reports
were retried. All actual release assets were verified before deployment.
[PR #26](https://github.com/RavoxX/MCVoice/pull/26) adds a mandatory completeness
gate with 12 passing regression tests: missing planned versions/loaders,
failed/skipped publications, failed gates and unverified Maven read-back cannot
produce a successful release. It does not rebuild or alter the 0.1.15 jars.

Four additional artifact-selection tests cover reruns with nonchronological
IDs and safe record extraction. Full
[report recovery](https://github.com/RavoxX/MCVoice/actions/runs/41903628934)
selects the newest status/result per name by creation time, validates the
original source, supported plan and successful validation gates, and regenerates
the complete report. It does not rebuild or republish jars, Maven or images.

[26.3 runtime](https://github.com/RavoxX/MCVoice/actions/runs/41888419707)
passes Fabric and Forge: fresh/saved options, native text and key/mouse controls,
HUD extraction, world entry/focus and shutdown. Both published production jars
match these preserved artifacts. **Fabric 26.3 requires loader 0.20.1 or newer.**
The harness now reads the exact generated loader/API and Forge versions rather
than selecting an older cached installation. An initial preparation run failed
that mismatch; no runtime check was relaxed.

Real SVC **2.6.27** probes pass on Paper
[1.18.2](https://github.com/RavoxX/MCVoice/actions/runs/41880220109),
[1.19.4](https://github.com/RavoxX/MCVoice/actions/runs/41880226501),
[1.20.1](https://github.com/RavoxX/MCVoice/actions/runs/41880233064) and
[1.21.4](https://github.com/RavoxX/MCVoice/actions/runs/41880166900): incoming
synthetic speech, sender restart, departed-user removal, automatic socket
recovery and fresh-key renewal. The tested SVC source is unchanged in 0.1.15.
No SVC code/assets are included. Human microphones and two full game clients
remain manual coverage.

Additional runtime preparation covered both 1.16.5 loaders. A four-target repeat
ended cancelled after its 1.21.1 Forge job stalled; it is not reported as a
successful workflow. Fresh [1.21.1 runtime](https://github.com/RavoxX/MCVoice/actions/runs/41886477921)
passes both loaders after retrying a GitHub artifact-download reset before
Fabric launched. These older preparation jars are not asserted to equal the
0.1.15 release byte for byte.

The interrupted 0.1.14 attempt published partial immutable Maven artifacts and
images but was never deployed. It is not a full release; 0.1.15 uses a fresh
version and does not replace those Maven artifacts.

## Deployment

Rust 0.1.15 was [deployed](deployment-0.1.15.md) after isolated staging and
completed release verification. Local, public and independent workstation checks
pass. All 33 unrelated container identities, authentication, proxy trust and
ports are unchanged. The previous production 0.1.13 image and protected backup
remain for rollback; operational instructions stay local.
