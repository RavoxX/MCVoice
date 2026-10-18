# Minecraft 26.3 settings input (follow-up to 0.1.6)

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
visual or physical-pointer test. Final results are recorded after execution.

MCVoice 0.1.6 was already published when the settings issue was reported.
The follow-up requires a new version; existing 0.1.6 jars/Maven artifacts are
preserved. Candidate runtime builds may use the `mc-startup.yml` `version`
input without publishing anything.
