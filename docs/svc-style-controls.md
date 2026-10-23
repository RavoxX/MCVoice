# Speaking controls and group types (0.1.12, release preparation)

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
Forge modifier combinations can be configured in Minecraft's own Controls;
existing combinations, including modified old-default keys, are preserved.

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

## Validation

Runtime source `18c1fa7` passes [CI 40007617453](https://github.com/RavoxX/MCVoice/actions/runs/40007617453)
(all six jobs), [backend container checks 40007617458](https://github.com/RavoxX/MCVoice/actions/runs/40007617458)
and [26.3 builds 40007631849](https://github.com/RavoxX/MCVoice/actions/runs/40007631849).
The local Java 8 client suite passes 126 tests with no failures or skips against
each backend. Go passes race tests and vet; Rust passes strict Clippy, 15 unit
tests and seven vector suites. Both backends pass all 43 shared conformance
scenarios, including admission through member twenty, rejection of member
twenty-one, capability downgrade refusal and encrypted UDP group policies.
Client tests cover real group/proximity delivery, private-group SVC suppression,
isolated SVC playback, encoding-time group changes, migration and native binding
capture. Full twenty-group/twenty-member directory pages fit the unchanged
64-KiB control limit in both backends.

All loaders built and validated for these representative versions at `711282e`:
1.8 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005903438)),
1.8.9 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005906877)),
1.9 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005910611)),
1.12.2 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005913356)),
1.13.2 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005916623)),
1.14.4 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005919990)),
1.16.5 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005923212)),
1.20.1 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005926447)),
1.21.5 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005929188)),
1.21.6 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005932613)),
1.21.9 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005935726)),
26.2 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005938655)) and
26.3 ([run](https://github.com/RavoxX/MCVoice/actions/runs/40005941659)).
The later runtime change adds only the shared member-scroll hint; adapter code
is identical. This is representative API coverage, not a new all-version release
or an update to the generated stable support matrix.

The initial [26.3 native runtime run](https://github.com/RavoxX/MCVoice/actions/runs/40005944132)
passes Fabric and Forge. Final input/rendering evidence for `18c1fa7` is collected
in [run 40007635099](https://github.com/RavoxX/MCVoice/actions/runs/40007635099):
fresh and saved options, native SDL text fields, actual registered keyboard/mouse
rebinding and clearing, custom Forge modifiers, and clean shutdown. Its separate
test mod captures the production Voice Chat, Key Bindings, group-directory and
twenty-member group screens through Minecraft's real GUI extractor. Screenshots
are fixture-driven visual evidence; they do not demonstrate twenty live people
or real microphone hardware. Real audio delivery and policy enforcement are
covered by the independent client/backend regression suites above.
