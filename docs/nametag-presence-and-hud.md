# Voice indicators, update links and HUD editing

The client now shows an original speaker icon after an already visible player
nametag while that player is using MCVoice or Simple Voice Chat but is quiet.
Speaking retains the green microphone. Muting that player in MCVoice shows a
speaker with a red strike, including while they are quiet; mute takes priority
over speaking. Another player's private microphone mute setting is not inferred.

Presence comes from the current cloud session/peer list, current voice group or
connected SVC player metadata. A tracked entity alone, old audio, or a remote
group member without a current local entity cannot create a nametag indicator.
World exit, disconnect and departed-user metadata clear it. H / Hide Voice Icons
still hides all indicators. Vanilla visibility, team/invisibility rules, distance
and formatted names remain under the renderer's existing checks. Every playback
and routing check is preserved; indicators never grant audio permission.

The shared Java 8 pixel art generates both the modern font atlas and legacy
immediate-mode rendering. No SVC artwork is included.
Speaker rows and group names use the same visual baseline centering as the
Minecraft-style buttons, keeping text centred beside the icon/head.

## Edit HUD

The microphone and group roster have four visible corner handles. Drag a corner
to resize; drag inside an element to move it. Group resizing changes every head
together. The opposite corner stays fixed as space allows, and elements remain
within the viewport. Sizes retain the existing 10–32 pixel bounds and saved
configuration fields. The two size sliders are removed. Speaker list orientation
and background controls remain. Done saves; Cancel/Escape discard; Reset changes
the draft until Done. Layout remains independent of GUI scale.

## Updates

Release links use the operating system's desktop launcher on a worker, without
AWT's headless desktop dependency or shell command parsing. Only validated MCVoice
GitHub release pages are accepted. The menu shows browser-request feedback and
keeps the readable URL available if launching fails. Update checks and Done remain
ordinary screen buttons.

## Verification

The Java client suite has 148 tests; core end-to-end tests also run against the
other backend. Quiet/muted/departed cloud and SVC users, all four microphone
resize corners, draft settings and release URL validation have regression
coverage. CI also checks Java 8 bytecode and all 44 shared conformance scenarios
against each backend.

[Native UI run 43295660492](https://github.com/RavoxX/MCVoice/actions/runs/43295660492)
passes Fabric and Forge on both 26.2 and 26.3. Real OS mouse events click every
Updates button and drag microphone/group corners. The headless browser launcher
records the exact expected release URL; this proves launch dispatch, not that an
external browser displayed a page. The isolated test mod never enters production
jars. Screenshots and positive completion markers are retained in the run artifacts.

[26.3 startup run 43294171286](https://github.com/RavoxX/MCVoice/actions/runs/43294171286)
passes both loaders with fresh and saved configuration, world entry, native input,
HUD rendering and clean shutdown. Both production jars match the later native UI
artifacts byte for byte. The SVC wire implementation is unchanged, so no new
real-server SVC interop result is claimed.
