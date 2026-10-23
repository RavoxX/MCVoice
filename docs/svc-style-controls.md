# Speaking controls and group types (0.1.12-dev, unreleased)

The controls follow Simple Voice Chat's documented user model, implemented with
MCVoice's own code, widgets and artwork. No SVC source, assets or sounds are
included. The server-plugin-free backend, cross-server groups, group directory
and adjustable HUD remain MCVoice features.

## Speaking and controls

Press **V** to open Voice Chat. Its buttons open Settings, Groups and Key
Bindings, mute the microphone, disable voice chat, and hide/show icons. Settings
offers audio devices, microphone testing, PTT/voice activation and its threshold.
Key Bindings captures native keyboard and mouse input, saves Minecraft's
registered mappings immediately, and uses Escape to clear a binding. Short
screens scroll to expose every binding. Minecraft's Controls remains usable.

| Action | New default |
|---|---|
| Voice menu | V |
| Push to talk | Unbound: choose a key in Key Bindings |
| Whisper | Unbound: choose a key if wanted |
| Mute/unmute microphone | M, in voice-activation mode |
| Disable/enable speaking and listening | N |
| Hide/show voice icons | H |
| Groups | G through 1.21.5; unbound from 1.21.6 |
| Direct Settings / status | Unbound |

There is **one microphone activation mode**. PTT uses the chosen talk key for
nearby and group speech; voice activation uses the configured threshold for
both. Joining a group never independently opens the microphone. The microphone
button and M hotkey act in voice activation only. In PTT they are inactive:
the held talk/whisper key controls transmission. A saved VA mute preference
does not block PTT, and is restored when switching back to voice activation.
N gates all transmission/playback. H preserves the individual
HUD visibility preferences, so toggling it back restores the previous layout.
Hotkeys are consumed without activation while typing/binding inside a menu.

Holding Whisper transmits without also holding PTT. Nearby whisper range
remains configurable; in a group it obeys the selected group's policy and does
not turn a private group into a proximity broadcast. Audio processing, normal
distance settings and local-entity/range validation are otherwise unchanged.

The current SVC defaults are documented in its [key bindings](https://modrepo.de/minecraft/voicechat/wiki/key_bindings)
and [client setup](https://modrepo.de/minecraft/voicechat/wiki/client_setup).
MCVoice provides device/mode selection through its existing Settings instead
of copying SVC's onboarding wizard or recordings UI.

## Groups: twenty members and explicit policies

| Type | Group members hear each other | Members hear nearby outsiders | Nearby outsiders hear members |
|---|---|---|---|
| Normal (new default) | Yes | Yes | No |
| Open | Yes | Yes | Yes |
| Isolated | Yes | No | No |

The type is selected before creating a group, independently of its optional
password. It stays fixed for that group's lifetime. Group speech is centred,
without distance attenuation, and only current members can hear it. Nearby
speech always retains every local-world/entity/visibility/range check.
Isolated membership also rejects SVC positional audio and already queued
positional frames at playback. Normal/Isolated microphones are never sent to
SVC proximity. Encoding rechecks membership policy before sending, covering
group switches during an audio frame.

Both backends admit members 1–20 and reject the 21st with `group_full`. Rosters,
the draggable HUD and group screens support all twenty; short member lists
scroll. Directory pages still contain at most twenty **groups**: that separate
paging limit and all password/request budgets are unchanged. A twenty-group
page of twenty-member rosters remains within the existing 64-KiB control bound;
no frame or request limits were raised to accommodate it.

SVC's [group type contract](https://voicechat.modrepo.de/de/maxhenkel/voicechat/api/Group.Type.html)
defines these choices. MCVoice groups remain separate from SVC groups.

## Upgrade and compatibility

The one-time `controlsVersion` migration clears old MCVoice defaults V for PTT
and B for Whisper; G is cleared on 1.21.6+. Other saved keyboard/mouse bindings
are preserved. New V/H defaults are cleared if they conflict with another
MCVoice action. Choose a talk key through the menu; no custom keyboard or mouse
binding is silently replaced with a new talk key. Saved invalid negative SDL
keys continue to migrate to Minecraft's actual unknown key.

Old `groupActivationMode` is ignored and no longer saved. The existing global
`activationMode` is retained, preventing an old group open-mic preference from
overriding a user's PTT choice.

The optional `group_modes` capability protects compatibility. Old clients still
create/join legacy Open groups; new backends return their actual capacity of
twenty. Normal/Isolated groups require capable clients, including for joining,
so a legacy SVC playback path cannot violate isolation. On an old backend, the
new client requires explicitly selecting Open; it refuses to silently create
Normal/Isolated groups as open broadcasts. Unknown/malformed policies fail
closed. The type is backend-owned routing state, never trusted from audio flags.

This is an unreleased development change. The deployed public backend and
published 0.1.11 clients retain [their recorded behavior](release-0.1.11.md).
Regression/runtime and build results will be recorded after validation.
