# 0.1.17 client release evidence

0.1.17 adds quiet/muted voice-user nametag indicators, replaces HUD size sliders
with corner handles, centres HUD name text beside icons/heads, and fixes browser
launching from Updates without AWT's headless desktop dependency. See
[behaviour and regression coverage](nametag-presence-and-hud.md).

Source is `db027280821f8adfdc0f3bd9c970598d9afb16c0` (PR #28 plus the
test-only correction in PR #29). [Main CI 43299289712](https://github.com/RavoxX/MCVoice/actions/runs/43299289712)
passes: 148 client tests, core end-to-end tests against both backends, Java 8
bytecode, backend checks and all 44 conformance scenarios per backend.

The earlier main run `43297727936` failed because its random token mutation
could preserve the decoded signature. PR #29 changes significant signature bits
deterministically and retains the strict rejection assertion. Production
authentication and token formats are unchanged.

## Runtime evidence

* [Native UI 43295660492](https://github.com/RavoxX/MCVoice/actions/runs/43295660492)
  passes all four targets: 26.2/26.3, Fabric/Forge. Real OS mouse events exercise
  every Updates button and microphone/group corner resizing. A separate launcher
  stub records the exact expected GitHub URL; no external browser page is claimed.
* [Full 26.3 startup 43294171286](https://github.com/RavoxX/MCVoice/actions/runs/43294171286)
  passes both loaders: fresh and saved configuration, world entry, native input,
  HUD extraction/rendering, focus and clean shutdown. Its production jars equal
  the later native UI jars byte for byte.
* Positive completion markers, logs, screenshots and exact production jars are
  in those runs' artifacts. All eight relevant game logs pass the runtime log
  checker. Test classes are absent from production jars.
* [Runtime SHA-256 record](runtime-0.1.17.json). Native UI sources are
  `848cac42`; full startup sources are `d0183085`. Subsequent changes are tests
  and documentation only. SVC protocol code is unchanged; no new real-server
  interop run is claimed.

## Publication

[Release 43300510219](https://github.com/RavoxX/MCVoice/actions/runs/43300510219)
passes: 256 successful jobs and one intentional skipped backend-image job.
All 102 jars were built and validated, all 62 Minecraft GitHub releases were
created, and all 102 Maven packages were published and downloaded for byte
verification. The complete-publication gate passes; `release-report.md` is
attached to [v0.1.17](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.17).

Independent verification checks every GitHub jar asset digest against its Maven
read-back digest and published checksum, and every tag against the preserved
generated branch head. All match. Downloads of all four published 26.2/26.3 jars
equal the native-UI-tested jars byte for byte; both 26.3 jars also equal the full
startup-tested jars. None contains test-mod classes. The generated supported
matrix was refreshed from the 102 successful CI records and validates without
errors; no supported version was added by hand.

See the [102-jar verification record](release-0.1.17-verification.json).
Backend images are excluded from this client-only release; production Rust
remains 0.1.16. No backend deployment was needed or performed.
