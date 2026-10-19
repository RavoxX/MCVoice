# Minecraft 26.3 settings input (follow-up to 0.1.6)

## Native text-input follow-up (unreleased)

The earlier event-forwarding tests did not verify that SDL text input was
actually enabled. Unlike GLFW, 26.3 requires an explicit `TextInputManager`
owner before the operating system emits text events. Shared text fields are
self-drawn and never focus a vanilla EditBox, so the service remained stopped.
`UiScreen.textInputFocused()` now bridges real field focus to
`Minecraft.textInputManager()` after clicks, navigation and layout changes.
Removing a screen stops only that screen's text-input ownership.

The runtime harness now checks `SDL_TextInputActive` when a field receives
focus, dispatches through `KeyboardHandler.textInput` with the actual screen
installed, and verifies blur/removal stop SDL input. Player-search fields also
assert native activation. These checks still do not simulate a physical keyboard
or an IME. Earlier versions retain their existing character-input path.

Non-publishing runtime run
[39264685676](https://github.com/RavoxX/MCVoice/actions/runs/39264685676) passed
both Fabric and Forge from `cbf430c`. The production wrapper and harness also
compile locally against the real 26.3 APIs; screen access in the probe uses
`Minecraft.gui.screen()/setScreen`, which differs from earlier versions.

## Published mouse/key event correction

Minecraft 26.3's SDL mouse events use left button 1, right button 3, and
middle button 2. MCVoice's shared `UiScreen` contract uses left 0, right 1,
and middle 2. `McScreen` forwarded the native value, so the shared buttons
and sliders rejected left clicks. This is separate from the startup-key
crash fixed in the published 0.1.6 release.

The follow-up normalizes click, drag, and release events at the platform
boundary, including extra mouse buttons. It also converts SDL navigation
scancodes to `UiScreen`'s normalized key codes, restoring text-field Backspace,
Enter, Tab, Escape and navigation handling. Character input still uses native
character events. Earlier GLFW versions retain their original input values.

The dedicated 26.3 runtime test now passes Minecraft event objects through
the production `McScreen` wrapper into real shared settings widgets: left-click
toggle, right-click rejection, Audio/Players/General tabs, slider drag/release,
Edit HUD, Done, and character/Backspace/Enter text input. Separate assertions
cover all mouse phases, button mappings and normalized navigation keys.
These are programmatic event tests inside both running loaders, not a manual
visual or physical-pointer test.

Runtime run [39138274562](https://github.com/RavoxX/MCVoice/actions/runs/39138274562)
passed both loaders with candidate version 0.1.7 from source
`e89b65bcc471a0bd0c992aab77f7cac9066fea2f` (input correction `aa4cac1`). Both
fresh logs record `MCVOICE_SMOKE settings-input PASS`, followed by successful
world focus and shutdown. Both saved-options Quick Play launches also passed,
retaining valid keyboard/mouse bindings and Forge modifiers. The downloaded
candidate jars independently passed `validate_jar.py`.

Main CI run [39138258546](https://github.com/RavoxX/MCVoice/actions/runs/39138258546)
passed all six jobs, including the shared client/end-to-end suite and release
report regression tests. The original runtime attempt was canceled because
HeadlessMC prompted before re-downloading a cached Minecraft installation;
setup now reuses the exact pinned installed version without prompting.

MCVoice 0.1.6 was already published when the settings issue was reported.
The fix is now published as
[v0.1.7-mc26.3](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.7-mc26.3)
for Fabric and Forge, exclusively for Minecraft 26.3. Both release jars match
the runtime-tested candidate jars byte for byte. Existing 0.1.6 jars/Maven
artifacts are preserved. See `docs/release-0.1.7.md` for checksums, source and
publication evidence. Candidate runtime builds may use the `mc-startup.yml`
`version` input without publishing anything.
