# MCVoice 0.1.12

Stable [release 40012401858](https://github.com/RavoxX/MCVoice/actions/runs/40012401858)
uses merged source `39d375f58d870adcf01777c9d2701138adf7e463` for all 62 supported
Minecraft versions: **102/102 validated jars**, **62/62 GitHub Releases**,
**102/102 Maven publications**, and both tested backend images (`0.1.12` / `latest`).

[Verification evidence](release-0.1.12-verification.json) matches every GitHub jar
asset digest to the Maven jar downloaded and byte-compared by the isolated
publisher. Both 26.3 jars match the runtime-tested production jars byte for
byte. All 62 generated version branches contain the release source, and their
release tags match the verified branch heads.

## Controls and upgrade

**V** opens Voice Chat; **M** mutes voice activation, **N** disables speaking
and listening, and **H** hides/shows voice icons. Talk and Whisper start
**unbound**: choose keys under V > Key Bindings. Native keyboard and mouse
bindings are saved to Minecraft's actual registered mappings. The one-time
migration clears old MCVoice V/B defaults, preserving other custom bindings
and Forge modifiers. G remains the Groups shortcut through 1.21.5 and is
unbound from 1.21.6 onward.

One PTT/voice-activation mode now controls nearby and group speech. Joining a
group never independently opens the microphone. Groups admit **twenty** members
and reject the twenty-first. **Normal**, the new default, keeps speech inside
the group while allowing nearby listening. **Open** also broadcasts nearby;
**Isolated** hears only members. Backend-owned policies and per-frame client
checks cover cloud and SVC transports, including already queued playback and
encoding-time group changes. Local entity, world, range, authentication,
visibility and group-membership checks remain in force.

The optional `group_modes` capability prevents unsafe policy downgrades. Old
clients retain legacy Open groups; Normal/Isolated require capable clients.
MCVoice groups remain separate from SVC groups. See
[behavior, migration and compatibility](svc-style-controls.md).

## Validation

The release's six CI gates and [merged-main CI](https://github.com/RavoxX/MCVoice/actions/runs/40011188162)
pass. The Java 8 client suite has 126 tests against each backend; both backends
pass all 43 shared conformance scenarios. Go race/vet, strict Rust Clippy and
release tests, protocol vectors, Java 8 bytecode and load smoke checks pass.
Full twenty-group/twenty-member directory pages fit the existing control-frame
bound without raising frame or request limits.

[Exact-version 26.3 runtime 40010481218](https://github.com/RavoxX/MCVoice/actions/runs/40010481218)
passes Fabric and Forge with fresh/saved options, native SDL text input, actual
keyboard/mouse rebinding and clearing, modified old-default keys, world entry
and clean shutdown. The menu/group screenshots were inspected on both loaders.
The fixture-driven screenshots complement independent real-backend audio tests;
they do not stand in for twenty human microphone clients.

## Production

Rust 0.1.12 was [deployed and verified](deployment-0.1.12.md),
after release validation and image publication, while client builds continued.
Isolated staging and local/public readiness and protocol checks pass, including
an independent workstation check. Production advertises `group_modes`.
Authentication, port and trusted proxy settings are preserved; unrelated
services were unchanged. The previous 0.1.11 image and protected configuration
backup remain available for rollback. Detailed operations are retained locally.

These are deployment and regression checks, not a new live Mojang account-login
or human-to-human audio test. Remaining shared-state and relay scaling limits
and the practical scaling plan remain in [the hardening report](hardening-0.1.10.md).
