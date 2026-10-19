# MCVoice 0.1.7 for Minecraft 26.3

Published release: [v0.1.7-mc26.3](https://github.com/RavoxX/MCVoice/releases/tag/v0.1.7-mc26.3).
This stable client release fixes unresponsive settings buttons and sliders in
Minecraft 26.3 by translating SDL mouse buttons and navigation keys at the
platform boundary. It retains the startup and saved-binding fixes from 0.1.6.
Replace the older MCVoice jar with the jar for your loader; keep configuration.
See `docs/minecraft-26.3-input.md` for the cause and regression coverage.

Release workflow [39139446951](https://github.com/RavoxX/MCVoice/actions/runs/39139446951)
ran from `main` with `version: 0.1.7`, `minecraft: 26.3`, `prerelease: false`,
and `images: false`. All six CI jobs passed. JDK 25 builds and jar validation
passed for Fabric and Forge, both Maven packages were newly published, and
the image job was skipped. No backend images were built or deployed, other
Minecraft versions were not released, and older artifacts were not replaced.

Jar/tag source: `e7873fdd57dbc13f70e87e222fccaa1eec6c7b06`.
Workflow/umbrella-tag source: `57a05390347d444558afcedfd124d7e2635d8cbb`.
The build log's checkout revision and published tags were checked separately;
the status JSON's `commit` field records the workflow source.

| Loader | SHA-256 |
|---|---|
| Fabric | `b6d7f4d181271037d78a7791a6d8082c97c887cdaa1899955894e5893744eabd` |
| Forge | `0ffb0905aa29212494b4a84296430192c000bf01261c9256cfe94f9836d4dfa7` |

Both downloaded release jars match the published checksums and are byte for
byte identical to the 0.1.7 jars from runtime run
[39138274562](https://github.com/RavoxX/MCVoice/actions/runs/39138274562).
Their loader metadata requires exactly Minecraft 26.3, and neither jar contains
the separate runtime test mod. Independent jar validation passed: 371 classes
in Fabric, 370 in Forge.

That runtime run used Fabric Loader 0.19.5 / Fabric API 0.163.0+26.3 and
Forge 66.0.10 on Java 25. Both loaders passed fresh main-menu startup, settings
widget event tests, world entry, mouse focus, shutdown, and saved-options Quick
Play. Invalid saved keyboard bindings were repaired while valid custom keys,
mouse bindings, and Forge modifiers survived. The vanilla negative control
reproduced the original out-of-bounds exception. These are real-game runtime
checks, separately from compilation and the shared client tests.

Maven coordinates:

* `io.github.ravoxx.mcvoice:voice-client-fabric:0.1.7+mc26.3`
* `io.github.ravoxx.mcvoice:voice-client-forge:0.1.7+mc26.3`

Actions verification run
[39139963444](https://github.com/RavoxX/MCVoice/actions/runs/39139963444)
authenticated to Maven and compared both downloaded packages byte for byte
with the release jars. It attached the
[verified report](https://github.com/RavoxX/MCVoice/releases/download/v0.1.7/release-report-run39139446951.md)
to both releases, with 2/2 jars, 1/1 Minecraft release, 2/2 Maven publications,
source revisions and no images. The verification did not rebuild or republish
jars or Maven packages.

Runtime limitations: Linux/Xvfb/software graphics and offline worlds; settings
tests send Minecraft event objects through the production adapter into shared
widgets. Windows, physical-pointer interaction, visual quality, the original
modpack/profile, online multiplayer, and real voice/audio were not tested.
The original profile was unavailable; the upgrade run reused its first
launch's actual configuration and world with a saved-binding regression fixture.
