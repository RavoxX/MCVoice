# MCVoice 0.1.6 for Minecraft 26.3

This client-only release is scoped to Minecraft 26.3, Fabric and Forge.
Use the `release.yml` version override `0.1.6`, `minecraft: 26.3`,
`prerelease: false`, `images: false`. Other Minecraft releases and backend
images are outside this release.

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
Final workflow, publication and checksum evidence is recorded after release.

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
