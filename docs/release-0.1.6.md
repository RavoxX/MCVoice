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
