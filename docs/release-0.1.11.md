# MCVoice 0.1.11

Stable release [39965856784](https://github.com/RavoxX/MCVoice/actions/runs/39965856784)
uses merged source `45b096230a20dc0baeefc96231d609f454c9bd68` for all 62
supported Minecraft versions: **102/102 validated jars**, **62/62 GitHub
Releases**, **102/102 Maven publications**, and both tested backend images
(`0.1.11` / `latest`).

[Verification evidence](release-0.1.11-verification.json) matches all 102 GitHub
asset digests to the Maven jars downloaded and byte-compared by the isolated
publisher. Both published 26.3 jars also match the runtime-tested artifacts
byte for byte. All 62 generated version branches contain the release source;
their release tags match the verified branch heads.

## Changes

Outside a group, the voice group browser shows member heads with full styled
name tooltips and the first two names beside the group code (`, ...` for more).
Its saved All Groups / This Server filter uses the joined domain and port;
players need to join through the same address to appear in that filter.
Both backends implement the negotiated, bounded `group_directory` capability.
Rosters contain UUIDs/names, including password-protected groups, without
granting membership or audio playback rights. Routing and per-frame playback
authorization are unchanged. Older peers retain their compatible browser.

The Java 8 client optionally checks public GitHub releases in the background.
A newer stable release needs an uploaded jar for the exact Minecraft version
and loader. The HUD notice and Settings > Updates link lead to that release;
checks can be disabled. Nothing is automatically downloaded or installed.
See [behavior, privacy, compatibility and limits](group-directory-and-updates.md).

## Validation and release recovery

The release's six CI gates pass, including Java 8 bytecode, e2e tests against
both backends and the shared 41-scenario conformance suite. Merged-source
[CI 39964190570](https://github.com/RavoxX/MCVoice/actions/runs/39964190570)
also passes. Local Go vet/race, Rust release tests/strict Clippy, complete
client builds, vectors, version matrix and Gitleaks passed before release.

[26.3 runtime 39960281089](https://github.com/RavoxX/MCVoice/actions/runs/39960281089)
passes Fabric and Forge, including fresh menu/world, saved options, native
SDL text/settings input, group filtering, update-link actions, head/tooltip
rendering, focus and clean shutdown. Both loaders' screenshots were visually
inspected. The executable source matches the release; intervening commits
only finalized documentation and merged the reviewed change.

The first release attempt failed only on Forge 26.2: Gradle could not resolve
the ForgeGradle plugin from its repositories before compilation. The failed
log and all 66 artifacts requiring replacement by downstream rerun jobs were
preserved locally before removing those CI artifacts. The failed build and
dependent publication/report jobs were rerun against the same source. The
final workflow and all publications pass; no tests or validation were skipped,
and successful Maven packages were not replaced.

## Production

Rust 0.1.11 was [deployed at 14:08:14 UTC on 2026-10-23](deployment-0.1.11.md)
after the release CI gates and tested-image publication, while the client
matrix was still running. Isolated staging and public/local readiness and
protocol checks passed. Production reports 0.1.11 with `group_directory`;
Mojang auth configuration, exact ports and trusted proxy gateway are preserved.
All 30 unrelated containers retained their exact IDs. The previous 0.1.10
image and mode-600 environment backup remain available for rollback.

This is not a new human-to-human production audio or Mojang account-login
test, nor proof of 100k-user capacity. Remaining shared-state and relay scaling
limits and the practical plan remain in [the hardening report](hardening-0.1.10.md).
