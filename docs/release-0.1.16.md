# 0.1.16 release evidence

0.1.16 is a backend release: fixes and hardening from a review of the Rust
backend ([details](rust-backend-review-0.1.16.md)). The client code is
unchanged. By the owner's choice it was published for **Minecraft 26.3 only**,
together with both backend images. The other 61 Minecraft versions stay on
0.1.15 and get no update prompt.

* Source: `bf197b4923dd2b51e48b7318c2774feb59aacf24` (merged PR #27).
* PR CI `42893706591` and image build `42893706576` pass; main CI
  `42895594666` and image build `42895594595` pass on the merge commit.
* [Release workflow](https://github.com/RavoxX/MCVoice/actions/runs/42896428425):
  all 16 jobs succeed. 2/2 jars built and validated, 1/1 GitHub Release
  (`v0.1.16-mc26.3`), 2/2 Maven packages, both images (`0.1.16`, `latest`).
  The report is attached to `v0.1.16` as `release-report-run42896428425.md`.
* Rust image `ghcr.io/ravoxx/mcvoice/voice-backend-rust:0.1.16`, digest
  `sha256:9aba27d37a98d5849001e9fa529b217119aed69496788c46c606c001d01ee362`
  (`latest` resolves to the same digest).

| Jar | SHA-256 |
|---|---|
| `mcvoice-0.1.16-mc26.3-fabric.jar` | `ac9c2bf20f31814652e9e699616d5d8a90c20e3a6ad68f32de5681498f4590a1` |
| `mcvoice-0.1.16-mc26.3-forge.jar` | `a34d13b940a5adff9d9029785a682d879970d45cc9bd9d2c7a70a3d993c70f5f` |

## Not covered

* No 26.3 runtime startup test (`mc-startup.yml`) was run for these jars. They
  were built and validated by the release workflow only. The regenerated
  `mc/26.3` build uses Fabric API `0.165.2+26.3` (0.1.15 used `0.165.0+26.3`),
  following the version matrix on `main`.
* Real-SVC interop was not re-run: `client/svc-compat` is unchanged.
* The Go backend only received the version number. It does not enforce the
  new resume-chain lifetime.
