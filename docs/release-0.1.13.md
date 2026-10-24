# MCVoice 0.1.13

Stable [release 40122108949](https://github.com/RavoxX/MCVoice/actions/runs/40122108949)
passed all 260 jobs from merged source `39acc90c54d4d75b6febe4cb5b18480fc470872a`
([PR #22](https://github.com/RavoxX/MCVoice/pull/22)):
**102/102 validated jars**, **62/62 Minecraft releases**, **102/102 Maven
publications**, and both tested backend images (`0.1.13` / `latest`).

The [verification record](release-0.1.13-verification.json) matches every GitHub
jar asset digest to the Maven jar downloaded and byte-compared by the isolated
publisher. All 62 tags match the verified generated branch heads. Both 26.3
release jars match the exact runtime-tested production jars byte for byte.

## Security fixes and compatibility

Issues #17–#21 were confirmed and addressed: maximum-counter replay advancement
in Go, detached-session group/peer mutations, SVC CBC downgrade and packet
replay, and JVM option injection in the developer probe. See [boundaries,
regressions and limits](security-0.1.13.md) for the per-issue changes.

SVC now accepts authenticated AES-128-GCM only. Its bounded exact packet-identity
history survives silence, jitter reset and same-key reconnects. Real SVC reuses
speaker channels and restarts sequences; fresh encrypted speech remains valid
when a speaker rejoins. Before history fills, MCVoice expires UDP and requests
a different key through Minecraft. **This briefly pauses SVC audio** (about nine
seconds in the tested server; about 16 minutes of one continuous incoming
50-frame/s stream reaches the renewal threshold, sooner with several speakers).
A server returning the same key cannot clear history; failure to obtain a fresh
key within 60 seconds fails closed. These are explicit limitations of securing
the existing SVC protocol without receiver-visible epochs or numbered controls.

Normal/Open/Isolated groups, their twenty-member cap, Java 8 compatibility,
privacy, authentication and every positional/group playback check are preserved.
Unverified CBC-only SVC servers are unsupported. No 100,000-user capacity claim
is made; the existing [scaling limits and plan](hardening-0.1.10.md) remain.

## Verification

[Merged-main CI](https://github.com/RavoxX/MCVoice/actions/runs/40121371968)
and all six release gates pass: Go vet/race/fuzz checks, strict Rust Clippy and
release tests, reproducible vectors, 43/43 shared conformance scenarios against
each backend, load smoke, Java 8 bytecode, and 133 client tests against each
backend. Nine Python boundary tests include a harmless JVM-agent fixture proving
that unsafe options cannot reach javap; a normal hardened
[26.3 probe](https://github.com/RavoxX/MCVoice/actions/runs/40121363920) also passes
inspection and separate diagnostic publication.

[26.3 runtime 40121061598](https://github.com/RavoxX/MCVoice/actions/runs/40121061598)
passes Fabric and Forge, including fresh/saved options, native SDL input,
keyboard/mouse controls, world entry and shutdown. Preserved production artifacts
from that run equal the published jars byte for byte. The runtime source
`95d4e4e` differs from the merge only in test code; byte identity is checked,
rather than inferred from source similarity.

Real SVC 2.6.26 tests pass on Paper
[1.18.2](https://github.com/RavoxX/MCVoice/actions/runs/40121617258),
[1.19.4](https://github.com/RavoxX/MCVoice/actions/runs/40121620451),
[1.20.1](https://github.com/RavoxX/MCVoice/actions/runs/40121623642) and
[1.21.4](https://github.com/RavoxX/MCVoice/actions/runs/40121371976): incoming
synthetic audio, sender disconnect/rejoin and fresh-key renewal. No SVC code or
assets are included. Real microphones and two full SVC/MCVoice game clients
remain manual coverage; the automated probes do not claim that coverage.

## Deployment

Rust 0.1.13 was [deployed](deployment-0.1.13.md) after isolated staging, all release
validation gates and image publication, while client builds continued. Local,
public and workstation checks pass; a later server recheck also passes.
The previous 0.1.12 image and protected backup remain for rollback.
