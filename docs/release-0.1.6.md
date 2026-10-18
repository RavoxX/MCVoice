# MCVoice 0.1.6 for Minecraft 26.3

This client-only release is scoped to Minecraft 26.3, Fabric and Forge.
Use the `release.yml` version override `0.1.6`, `minecraft: 26.3`,
`prerelease: false`, `images: false`. Other Minecraft releases and backend
images are outside this release.

Published release: [v0.1.6-mc26.3](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.6-mc26.3).
Release workflow [39135943359](https://github.com/RavoxX/MCVoice/actions/runs/39135943359)
completed successfully with all six CI jobs passing and images skipped.
Jar/tag source: `13c44c4bf4b43365d795f1a72b7562949d33a346`.
Workflow/umbrella-tag source: `9518ff05f1ef36b06b3da14d03f363d52deb8f1c`.
Both Maven packages were newly published as `0.1.6+mc26.3`.

| Loader | SHA-256 |
|---|---|
| Fabric | `98b8cd3e67927d260ea72ecd0ec8bb20f185388f452231e59b79e23a95a9b535` |
| Forge | `b03fa7fa2c81febc09c7baac505903819abc7a49c089aa338c561caac1d3fd31` |

Both downloaded release jars are byte-for-byte identical to the runtime-tested
0.1.6 jars. Metadata restricts Minecraft to exactly 26.3. No backend images
were built or deployed, and no 0.1.5 artifacts were replaced.

The original report rendered `0/0` because download-artifact flattened the
single matching artifact into its destination. The renderer now recognizes
both flat and nested layouts and has regression coverage. Actions-only report
recovery [39137283241](https://github.com/RavoxX/MCVoice/actions/runs/39137283241)
attached the [corrected report](https://github.com/RavoxX/MCVoice/releases/download/v0.1.6/release-report-run39135943359.md)
to both releases: 2/2 jars, 1/1 Minecraft release, 2/2 Maven publications,
images not requested. It also authenticated to Maven and verified that both
downloaded packages match the release jars byte for byte. The recovery did not
rebuild or republish jars or packages.

A subsequent report identified a separate settings-input problem in 26.3;
see `docs/minecraft-26.3-input.md`. That follow-up is not included in these
immutable 0.1.6 artifacts.

## Startup crash

MCVoice 0.1.5 registered its unbound settings/status actions with `-1`.
Inspection of Mojang's official 26.3 client confirms that SDL's unknown key
is `0` and `InputConstants.isKeyDown(int)` directly indexes the native keyboard
state buffer. `KeyMapping.setAll()` polls `-1`, causing the reported
`IndexOutOfBoundsException` when the mouse is grabbed. The reported
`LevelLoadingScreen.tick` path closes the loading screen when the level is
ready; this can occur during automatic world entry at launch, so testing only
manual world joins is insufficient. This does not establish that every
immediate-launch crash has this cause.

Commit `64eb846` corrected the defaults. The follow-up also normalizes saved
negative keyboard values when Minecraft calls an MCVoice mapping's `setKey`.
The options parser accepts `key.keyboard.-1`, so those values can otherwise
survive the upgrade. Valid keyboard and mouse binds are preserved; normal
options saves persist the repaired value as `key.keyboard.unknown`. No
configuration deletion or reset is required.

Forge 66.0.10's patched options loader calls `setKeyModifierAndCode` and
assigns the key field directly. The final correction also overrides this
setter, preserving valid Forge modifiers. A real saved-options regression
exposed this path after fresh-options startup passed; overriding only the
vanilla setter did not repair saved Forge keys.

## Verification

The dedicated `mc-startup.yml` workflow provides real-game regression coverage
for both loaders, separately from the shared Java tests and jar validator.
It checks the default unbound actions at the main menu, world loading and
mouse focus, then relaunches the saved world through Quick Play with an
upgrade fixture in `options.txt` and the existing generated `mcvoice.json`.
It also tests valid custom keyboard/mouse mappings and canonical saved output.
The vanilla negative control must reproduce the original exception.

The general `mc-smoke.yml` run 39133040090 successfully planned **no supported
26.3 runtime targets**: mc-runtime-test 4.5.1 has no 26.3 mod assets. This is
not a runtime pass. The local JDK 25 Gradle attempt failed creating a loopback
connection before compilation; Actions builds are the authoritative results.
Publication and checksum evidence is recorded after the release workflow.

Pre-release build run [39133635740](https://github.com/RavoxX/MCVoice/actions/runs/39133635740)
built and validated Fabric and Forge with JDK 25 from generated branch revision
`ebd38a17092f3640378863d1c5ab83c56ef6df02`, containing correction
`a2642506d88c9fcf04bdd1d639916ce32ba25e0f`. Both downloaded jars also passed
`validate_jar.py` independently. These CI build artifacts retain the repository
default version 0.1.5; the release uses the distinct 0.1.6 workflow override.
Main CI run [39133589871](https://github.com/RavoxX/MCVoice/actions/runs/39133589871)
passed all six jobs, including shared Java tests, client end-to-end tests
against both backends, and cross-implementation conformance/load smoke.

The official client inspected has SHA-1
`e877b6a07acd633fb3bb475002175cec036e7b87`, matching Mojang's version metadata.

Final pre-release build run [39135267447](https://github.com/RavoxX/MCVoice/actions/runs/39135267447)
passed both loaders from generated revision
`98c6a2f9bd2febaccd6f728060abb7c3b0ff681e`, including the Forge setter correction
`bdb7de208e01e8977de6856f60aaf230dc248220`.

Runtime run [39135257384](https://github.com/RavoxX/MCVoice/actions/runs/39135257384)
passed both loader jobs on that final correction. Downloaded artifacts contain
the fresh and upgrade logs, input/output options, reused MCVoice configuration,
pass markers and tested 0.1.6 jars. Both logs show `LevelLoadingScreen` followed
by in-game focus and clean shutdown, including the Quick Play launch. The
vanilla negative control reproduced the original exception on both loaders.
Settings/status saved as `key.keyboard.unknown`; custom P / mouse button 4
bindings survived, including Forge's Control / Alt modifiers. Independent
log checks also passed. No test classes entered the production jars.

The first harness attempts were not passes: a missing test-mod `pack.mcmeta`
blocked Forge behind a warning, multi-word launcher arguments were reparsed,
and an experimental test-world preset blocked Quick Play behind a backup
confirmation. The final harness supplies the metadata, passes JVM/game
arguments through HeadlessMC configuration, and uses a normal world preset.

Runtime coverage is Linux/Xvfb with software graphics and offline local worlds,
not a Windows or original-modpack reproduction. The original user's profile
was not supplied; the upgrade fixture retains the first launch's real config
and world, and injects the old invalid options plus valid custom bindings.
Online multiplayer, visual quality and real voice/audio delivery were not
tested. Headless audio-device and offline-account diagnostics are outside this
input regression; MCVoice runtime logs still have to pass the log checker.
