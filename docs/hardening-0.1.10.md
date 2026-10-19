# MCVoice 0.1.10 hardening

This follow-up addresses the supplied bug report against 0.1.9. Release and
deployment results are recorded after their workflows complete. Existing local
entity, range, world/epoch, mutual visibility, group membership, mute, AEAD and
replay checks remain mandatory.

## Security and compatibility decisions

* Java replay ages use unsigned comparisons. Duplicate current/previous key
  rotations preserve replay state and send counters; invalid IDs, conflicting
  keys and exhausted counters close/reject safely. Fresh byte-ID wrap remains
  valid. SVC mode negotiation commits only on the expected AUTH_ACK. The real
  Bukkit 2.6.24 server uses a 256-byte ACK buffer; this form is accepted only
  with valid GCM authentication, while CBC still requires the one-byte ACK.
  Malformed two-byte ACKs, padded CBC and tampered GCM remain rejected. Legacy CBC is
  still unauthenticated; this change fixes premature negotiation, not CBC's
  cryptographic limitations. Only GCM compatibility 20 has real-server coverage.
* Proxy headers are accepted only from explicitly configured
  `TRUSTED_PROXY_CIDRS`. Addresses are parsed and canonicalized; XFF is traversed
  from the trusted peer toward the first untrusted hop. Invalid/oversized chains
  fall back to the peer. Repeated HTTP header fields are combined in wire order,
  including empty fields, before applying the same bounds and trust walk.
  Enabling trust without CIDRs fails startup. Do not use
  broad private-network ranges when an exact gateway/peer address is available.
* Saturated limiter tables retain existing identities and use a bounded shared
  overflow budget. Evicting live identities would restore attack budgets and
  was deliberately avoided. Authentication-failure overflow no longer fails
  open. Group wrong-password budgets follow authenticated UUIDs across resumes
  and replacement. General group operation buckets remain connection scoped as
  specified; a global per-group password lockout would let attackers deny access
  to every legitimate member and was not introduced.
* Admission has a separate bounded pre-auth pool. Administrative policy reload
  and registration share hub-to-policy lock ordering; registration rechecks bans
  and administrative mute. Replacing one's own session works at capacity. Rust
  bounds streamed Mojang responses before retaining their bodies.
* Third-party test/build jobs have read-only tokens and no persisted Git
  credential. Diagnostic, image and Maven publication runs in separate jobs.
  Paper/SVC downloads require official API digests. Missing Maven artifacts
  retain successful build status while recording publication failure. Pull
  requests do not write the shared image cache.

## Correctness and performance

Concurrent client configuration collections and volatile shared values replace
unsafe cross-thread access. EOS ends cleanly without fake concealment. Capture
shutdown respects interruption, WebSocket control replies use a bounded worker,
writes have an independent socket timeout, and handshake failures close sockets.
Mic tests stop on tab/Done/native close; audio adjustments preview immediately.
Realms sessions no longer depend on a server-list address. Cloud group audio
bypasses positional transport selection and has independent decoder state;
current membership is still checked on every mixed frame. SVC consistently uses
the configured local listening range, rather than accepting frames that mixing
would subsequently reject.

Both relays reserve recipient counters under the UDP lock and encrypt voice
outside it. Presence read passes detect changes without allocating discarded
deltas; commit revalidates under the hub lock. Go moves attestation/visibility
construction out of the write lock, uses worker-owned forwarding scratch,
monotonic position ages, and correct replacement metrics/fatal-message drain.
Both implementations reject overflowing peer deltas atomically, ignore stale
position epochs before charging the interval, and prevent reordered HELLO
packets from moving UDP endpoints. Rust requests the same socket buffers as Go;
actual kernel limits remain deployment dependent.

Numeric container IDs, no Kubernetes service-account token, a single-instance
Recreate strategy, shell cleanup traps, and Windows filename/version validation
complete the tooling fixes. Java core remains Java 8.

## Validation and remaining limits

Regression tests exercise replay boundaries, duplicate rotations, malformed SVC
negotiation, stalled WebSocket writers, interruptible capture, config mutation,
mic-test lifecycle, group decoder isolation, Realms, proxy chains, saturated
budgets, admission/moderation, bounded auth bodies, resumed password guesses,
atomic peer deltas, stale epochs and reordered HELLO. Final command results,
benchmark measurements and release/deployment identifiers follow below.

The [scaling plan](input-and-performance.md#practical-path-toward-100000-concurrent-users)
still applies: one hub, periodic presence scans, per-recipient encryption,
single-host ownership and uncoordinated multi-instance groups remain limits.
100,000 concurrent users is an unproven target. No limits were raised to obtain
passing results.

Local verification passed: protocol vector reproducibility, version matrix,
Go formatting/vet/race tests, strict Rust release Clippy and release tests,
40/40 cross-implementation scenarios against Rust, and the complete Java 8
client build against each new backend (111 tests each, zero skipped/failing).
Four publication/download boundary tests, three release-report layout tests,
workflow syntax via actionlint 1.7.12 (without external shellcheck/pyflakes),
and staged/history Gitleaks scans passed. The Windows jar validator accepts
backslash paths and rejects both filename and metadata version mismatches.
Focused reply-counter exhaustion tests pass in both backends.

Fresh read-only investigation and post-patch review found two pipeline failure
paths: the SVC downloader's working-directory path and lost build status after
early Maven setup failure. Both were corrected; the latter has a regression
test. Every expected loader is now required before GitHub asset publication.

Remote CI `39280761322`, 26.3 runtime `39280764577`, and SVC 1.21.4 interop
`39280767193` were dispatched. As of 2026-10-19 16:00 UTC they are queued during
GitHub's Actions incident; these pending runs are not validation passes.

## Before/after measurements

[Raw samples and binary provenance](benchmarks/2026-10-19-hardening.json)
compare 0.1.9 (`0fef0da`) with measured 0.1.10 source (`b451681`, before the
additional repeated-XFF parsing correction; measured audio/presence paths are
unchanged by that correction). Windows
x64, Ryzen 7 5800X3D, one loopback host, three repetitions; no concurrent builds
during retained measurements. UDP runs alternate order, use four workers,
200 clients/60 talkers at 50 Hz, 5s ramp and 10s measurement. Values below are
medians of each run's statistics, not pooled percentile estimates. CPU includes
ramp, drain and shutdown; 100% means one core.

| Relay / visibility population | Delivery before → after | p99 ms before → after | Relay packets/s before → after | CPU % before → after |
|---|---|---|---|---|
| Go / 10 | 100% → 100% | 20.00 → 20.58 | 26,943 → 26,949 | 63.24 → 59.52 |
| Go / 50 | 28.29% → 28.20% | 7,365.52 → 7,404.71 | 41,579 → 41,443 | 85.12 → 83.19 |
| Rust / 10 | 100% → 100% | 2.50 → 2.66 | 26,961 → 26,926 | 35.51 → 35.72 |
| Rust / 50 | 100% → 100% | 15.22 → 16.65 | 146,800 → 146,685 | 152.76 → 152.48 |

Achieved sends were 2,987–3,000 frames/s, with zero cross-scope deliveries.
These samples establish no general relay latency/throughput gain. In particular
the dense Go case remains unusably saturated; its dropped packets and seconds
of backlog must not be hidden by quoting delivered-packet latency alone.

Presence uses the same fixture on each revision, with output queues drained
outside the timer. Changing presence alternates world membership for all peers;
this measures sweep work/allocation, not lock contention or a realistic churn
distribution. Three repetitions per case:

| Sweep | µs before → after | Bytes before → after | Allocs before → after |
|---|---|---|---|
| Stable / 100 | 102.10 → 97.40 | 0 → 0 | 0 → 0 |
| Stable / 1,000 | 1,292.86 → 1,292.44 | 0 → 0 | 0 → 0 |
| Changing / 100 | 498.39 → 386.80 | 173,211 → 123,549 | 1,208 → 708 |
| Changing / 1,000 | 5,292.76 → 3,826.39 | 1,673,479 → 1,175,900 | 12,025 → 7,021 |

Java 17 with 512MiB heap uses the preserved 0.1.9 classes and rebuilt Java 8
0.1.10 classes, alternating order. Tracking has 10k warmup/30k measured ticks;
mixing has 300 warmup/1,200 measured 20ms frames. Audible decode remains the
dominant cost. Separate group state fixes correctness but adds a small idle
check; this test does not establish a general mixing speedup.

| Client operation | µs before → after | Bytes/op before → after |
|---|---|---|
| Track 100 | 3.61 → 3.32 | 188 → 190 |
| Track 1,000 | 22.05 → 22.84 | 162 → 162 |
| Reject 8 speakers | 13.22 → 20.58 | 104 → 104 |
| Reject 32 speakers | 18.43 → 14.12 | 103 → 94 |
| Mix 8 accepted speakers | 493.79 → 525.55 | 218,834 → 218,460 |
| Mix 32 accepted speakers | 1,864.19 → 1,881.83 | 867,400 → 867,400 |

Remaining performance work: instrument actual hub/UDP lock wait and hold times,
profile dense Go socket forwarding on Linux, test presence churn with separate
load hosts, and implement measured sharding/dirty-presence scheduling before
raising capacity claims. Requested Rust socket buffers may be clamped by the
kernel; the request alone does not prove reduced loss.
