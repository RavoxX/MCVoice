# MCVoice 0.1.8

Client update for all 62 supported Minecraft versions (102 loader jars).
The backend and protocol are unchanged; backend images are not part of this release.

## Group roster

The default-on Group HUD shows all current group members as a vertical column
of player heads at the right edge. Speaking members get a white outline and
their formatted name beside the head. Quiet members stay visible. Minecraft's
default skin is used when the player is unknown to the local connection.

General settings has a Group HUD toggle. Edit HUD allows independent roster
dragging, a background toggle, and 10–32 px head sizing. Large groups fit down
to the available GUI height. Positions and appearance are saved in `hudLayout`;
Done commits edits, while Cancel/Escape discards them.

## Player settings

Names and volume/mute controls use the same snapshot and page bounds, fixing
the extra controls whose names disappeared on tall screens. Names use the
platform's formatted player-name renderer, with a full-width name row above
each player's controls. Search matches plain player names without regard to case
and keeps focus while results change.

The list includes confirmed cloud peers in the local world, current voice-group
members, and connected, enabled SVC users reported by the Minecraft server.
Tracked entities or old received audio alone do not establish voice presence.
SVC presence is independently implemented for observed compatibility 20 packets;
it does not change positional playback validation or enable SVC group voice.

## Validation

* All 85 Java client tests passed locally, targeting Java 8, including the real
  Go backend end-to-end tests. No tests were skipped. Local Gradle could not
  establish its loopback connection, so these used `javac --release 8` and the
  JUnit console runner. CI performed the full Gradle build.
* Main CI [39252773119](https://github.com/RavoxX/MCVoice/actions/runs/39252773119)
  passed all six jobs, including both backends, conformance, and client tests
  against both backends.
* Eleven representative build runs passed: 1.8, 1.8.1, 1.13.2, 1.14.4, 1.16.5,
  1.17.1, 1.19.4, 1.20.1, 1.20.2, 1.21.9, and 26.1.2.
* Real SVC server checks passed on Paper 1.21.4 (run `39252362910`) and 1.18.2
  (run `39252441826`), requiring that the probe's quiet voice user appears in
  decoded player-state metadata after authentication.
* Minecraft 26.3 runtime [39252773053](https://github.com/RavoxX/MCVoice/actions/runs/39252773053)
  passed Fabric and Forge. The separate test mod exercised SDL group visibility,
  dragging, head sizing, background controls and player search, plus actual
  production head rendering through `GuiGraphicsExtractor`. Fresh-world and
  saved-options Quick Play launches passed. Both runtime screenshots were
  inspected: a white-bordered speaking head with its name, and a quiet head.
  These tests do not establish real multi-user voice delivery in Minecraft.
* Both published GitHub Release 26.3 asset digests and the release-workflow jar
  artifacts match the runtime-tested jars byte for byte (SHA-256):
  * Fabric: `d2ba71f1a0bf52f985b26338d7b9f375e2c10da3e51457c9df2db0f84a2ff074`
  * Forge: `9ef9ecbb1b0781fbbeeb9e704c397f62a10d3432a7782cb6d56d4a7cdb10c236`

## Publication

All-version release run [39253618498](https://github.com/RavoxX/MCVoice/actions/runs/39253618498)
was dispatched with `minecraft=supported`, `prerelease=false`, and `images=false`.
The workflow completed successfully. Its published `release-report.md` confirms:

* 102/102 jars built and validated;
* 62/62 per-version GitHub Releases created;
* 102/102 Maven packages published;
* backend images not requested.

The generated support matrix was refreshed from all 102 recorded build results.
Download links for each Minecraft version are in the report attached to
[v0.1.8](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.8).
