# Rust voice routing optimization — 2026-09-26

The change removes recurring target-list allocations and shortens the presence
write-lock critical section. It does **not establish a general end-to-end
latency or CPU improvement**. The ordinary 500-client case delivered every
frame with both binaries and was slightly slower after the change. Saturated
cases varied substantially, including a worse initial 1000-client median.

## Changes and correctness

Implementation commit: `948bd584df188392e4aa84641589b7862df39115`.
Baseline: `584d33ff7aa1bf3529a3e6cbfc53c6ab93a605b9`.

- `backend/rust/src/server/udp.rs`: each UDP worker's `Scratch` owns a target
  vector. Clear, populate, temporarily take, drain, and restore it; capacity
  survives packets, and session references do not. A recipient losing its UDP
  address returns its unused output buffer to the existing pool. The protocol
  limits targets to at most 512 visible peers plus 14 group members: ordinary
  geometric growth retains at most 1024 target slots (16 KiB on this 64-bit
  build). Periodic shrinking would add avoidable allocation churn.
- `backend/rust/src/server/mod.rs`: presence computes additions/removals against
  existing sets instead of cloning every visible UUID into a fresh set each
  tick. Unchanged presence allocates no sets or UUIDs. It iterates peers directly
  instead of collecting connection IDs and looking them up again. Compute and
  commit remain under one write lock; sorting, serialization and sends happen
  afterwards. Jitter copies session references under Hub and releases it before
  taking any session UDP mutex.
- `backend/rust/src/server/http.rs`: the single maintenance task reuses presence
  update and jitter snapshot vectors, draining references after each sweep.
- `backend/rust/src/server/control.rs`: attestation verification and construction
  of full visibility sets happen before taking Hub. Epoch checks and state
  updates still happen under Hub; old visibility sets are dropped afterwards.
- `backend/rust/src/server/udp_tests.rs`: tests cover reuse without retained
  sessions/stale routes, current recipient epochs, group/proximity deduplication,
  presence diffs, and blocked UDP mutexes without holding Hub. A blocked relay
  also verifies the original captured recipient epoch is preserved.
- `tools/load-test/main.go`, `compare-rust.py`, `profile-rust.py`, `README.md`:
  exact seeded talker counts, deterministic movement,
  periodic visibility refreshes, refreshed post-ramp positions, receivers for
  reconnects, bounded drain, whole-run latency sampling, saved-binary comparison
  and disposable allocation/lock profiling. Expected fanout is computed with
  visibility updates instead of rescanning all clients for every voice frame.
- `AGENTS.md` and `docs/releasing.md`: current deployment and the backend-only
  image procedure. This report and `docs/benchmarks/rust-routing-2026-09-26.json`
  preserve the measurements.

`routing.rs` and the routing predicates are unchanged. Authentication, UDP
verification, mute/deafen, sender and recipient epochs, dimensions, attestation,
mutual visibility, current positions, freshness, range and group membership all
retain their existing checks. Every routing decision uses current Hub state
under its read lock. Only the same session reference, recipient epoch and group
flag as before leave that lock. Encryption and recipient/session locking remain
outside Hub. No cache, sharding, DashMap, wire change or new crypto was added.

## Method

Apple M5, 10 logical CPUs, 24 GiB RAM; macOS 27.0 (26A428). Rust 1.98.0,
Go 1.27.1. Both binaries used `cargo build --release --locked` with the same
lockfile/default release profile. Four Tokio threads and four UDP workers.
The same load-generator binary drives both revisions on loopback.

Each initial case has three paired runs, alternating binary order, with seeds
100/101/102, 5-second ramp, 1-second settle, 20-second measurement and 300-ms drain.
Exactly 30% talkers emit 60-byte synthetic payloads at a target 50 Hz; all clients
send positions at 5 Hz and full visible sets at 1 Hz. Churn and scope changes
are disabled for these comparisons. A group of 10 means nine visible peers;
25 and 50 mean 24 and 49. This is proximity grouping, not protocol voice groups.
Voice-group correctness is tested separately.

Latency is client send to recipient receive using the same host clock, sampled
on every sixteenth relay throughout the run. Counts include every relay.
Tables show **medians of the three per-run statistics**, not pooled percentiles.
CPU is backend user+system CPU from `wait4`, averaged over startup, ramp, settle,
measurement, drain and shutdown; 100% means one core. It excludes generator CPU.
No other builds or load tests ran during these measurement windows.

The generator and backend share the host; there is no CPU affinity or control
over unrelated desktop activity/thermal scheduling. Frames arrive in 20-ms
bursts. Saturated queues can outlive the drain. These are comparative local
observations, not production capacity guarantees or a statistical significance
claim. Preliminary tuning and instrumented runs are excluded from these tables.

## Initial before/after results

Every row lists **before → after**. All clients connected successfully and every
stable run recorded zero cross-scope deliveries.

| Clients / proximity group | Delivery ratio | Sent pps | Relayed pps | Backend CPU % |
|---|---:|---:|---:|---:|
| 500 / 10 | 1.0000 → 1.0000 | 7,497.9 → 7,497.4 | 67,481.0 → 67,476.7 | 92.05 → 93.17 |
| 500 / 25 | 0.7946 → 0.8303 | 7,497.3 → 7,499.7 | 142,980.0 → 149,420.1 | 206.42 → 206.22 |
| 500 / 50 | 0.3181 → 0.3218 | 7,499.8 → 7,499.7 | 116,849.5 → 118,273.5 | 201.69 → 204.00 |
| 1000 / 10 | 0.8749 → 0.8036 | 14,971.6 → 14,992.9 | 117,882.1 → 108,446.6 | 211.43 → 211.52 |

| Clients / proximity group | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|
| 500 / 10 | 4.360 → 4.407 | 8.053 → 8.162 | 8.572 → 8.750 |
| 500 / 25 | 693.500 → 670.260 | 714.223 → 695.394 | 788.273 → 706.635 |
| 500 / 50 | 1,823.864 → 1,786.388 | 2,271.442 → 2,194.669 | 2,361.019 → 2,267.812 |
| 1000 / 10 | 305.825 → 332.490 | 345.281 → 394.964 | 363.006 → 500.688 |

The 500/10 p99 rose 2.1%; CPU rose 1.12 percentage points. There is no measured
CPU saving. Larger visible sets improved their aggregate delivery and p99, but
remain heavily saturated. The initial 1000/10 median worsened and must not be
presented as a scalability improvement. Individual 1000/10 p99 pairs were
688.418→600.102, 363.006→500.688 and 352.004→376.180 ms.

### 1000-client repeat after cooldown

Because the initial results were inconsistent, the identical three pairs were
repeated after a 40-second cooldown following diagnostic/churn testing. The
first two pairs delivered everything; the third pair again accumulated backlog.
This repeat also does not demonstrate a latency improvement.

| Repeat median | Before | After |
|---|---:|---:|
| Delivery ratio | 1.0000 | 1.0000 |
| Sent pps | 14,999.4 | 14,999.2 |
| Relayed pps | 134,906.3 | 134,862.9 |
| p50 ms | 10.047 | 10.744 |
| p95 ms | 20.573 | 105.312 |
| p99 ms | 27.919 | 134.080 |
| Backend CPU % | 195.37 | 193.63 |

Individual p99 pairs: 20.060→30.949, 27.919→134.080 and
288.097→288.415 ms. Third-pair delivery was 0.9863→0.9747. Both binaries'
backend receive-to-send p99 stayed within the same 1-ms Prometheus histogram
bucket in all six repeat runs; this excludes time queued before the backend
receives a datagram. The end-to-end regressions are retained here, not explained
away as proven noise. The experiment does not isolate their cause. Allocation
and presence-lock improvements are established; overall tail-latency and CPU
improvement is not.

## What the profiling actually found

Separate disposable builds add allocator counters scoped to `relay_voice` and
Hub acquisition/hold timers. Each pair uses the same generator with a 10-second
measurement, otherwise the same settings. Clocks/atomics perturb execution, so
these runs are diagnostic and are not used for latency/CPU improvement claims.
Presence times include ramp and teardown ticks.

| Clients / group | Relay calls before / after | Allocation + reallocation calls before / after | Calls per relay before / after |
|---|---:|---:|---:|
| 500 / 10 | 75,000 / 75,000 | 225,040 / 52 | 3.00053 / 0.00069 |
| 500 / 50 | 34,578 / 34,304 | 172,964 / 228 | 5.00214 / 0.00665 |
| 1000 / 10 | 149,700 / 150,000 | 449,140 / 52 | 3.00027 / 0.00035 |

The original target vector grows 4→8→16 for nine recipients, or through 64 for
49 recipients, on every frame. Those recurring allocator calls are eliminated.
The small remaining totals are initial growth of worker-local targets and
existing packet/output buffers. Allocation byte volume was not measured.

| Clients / group | Mean presence write hold, ms before / after | Maximum presence write hold, ms before / after |
|---|---:|---:|
| 500 / 10 | 0.864 / 0.390 | 6.697 / 2.264 |
| 500 / 50 | 3.564 / 2.343 | 20.818 / 18.738 |
| 1000 / 10 | 1.561 / 0.897 | 8.719 / 7.086 |

At 500/10, mean routing read-lock wait was 0.036→0.035 microseconds, with the
p99 histogram upper bound unchanged at 0.128 microseconds. Mean routing lock
hold was 3.989→4.073 microseconds. The other read path (mostly session lookup)
had mean wait 0.064→0.063 microseconds and p99 upper bound 0.256 microseconds.
The mean of other Hub write holds fell 0.392→0.228 microseconds; mean write wait
fell 0.835→0.644 microseconds. There was no large steady-state routing read-lock
contention bottleneck in this profile.

The confirmed avoidable costs were allocation churn and periodic long presence
write sections. They were not the dominant explanation for saturated-run tail
latency. For example, initial baseline 500/25 sent 150,000 voice frames but the
backend received only 131,374 total UDP datagrams including 500 hellos by the
metrics snapshot; at 500/50 it received 54,336. No authenticated routing-drop
counters increased in those examples. Queueing/loss before routing and the
finite drain materially affect the result. The remaining CPU/IO bottleneck was
not isolated sufficiently to attribute it specifically to encryption, socket
handling or scheduling, so no architectural rewrite is justified here.

## Validation and deployment evidence

- `cargo test` and `cargo test --release`: 6 unit tests and 7 integration tests
  passed in each configuration, including routing/control/UDP/replay vectors and
  parser robustness tests. Existing correctness tests were not weakened.
- `cargo fmt --check` and release Clippy with `-D warnings`: passed.
- Protocol vector regeneration check: passed using cryptography 44.0.2.
- Black-box Rust conformance: **38/38 passed**, offline and mocked Mojang modes.
- Race-enabled Go load generator: 60 clients, 15 seconds, 37 reconnects, 7
  dimension changes and 9 server switches; exit 0 with no race reports. Its
  scope-observation counter was 41 during transitions (this metric also counts
  already-in-flight frames before control updates settle; it is not a routing
  conformance verdict). Delivery ratio was 1.0.
- [Full CI](https://github.com/RavoxX/MCVoice/actions/runs/36254844464): passed,
  including both backend suites, conformance, load smoke, secrets and client e2e.
- [Container build and smoke test](https://github.com/RavoxX/MCVoice/actions/runs/36254844465): passed.

The backend-only image is `ghcr.io/ravoxx/mcvoice/voice-backend-rust:sha-948bd584df18`,
with manifest digest
`sha256:3a0f02738ba2e68273d95020a7e9569d0f4f514477ebfc3ef1966ff30a95d245`.
Its image revision label matches the implementation commit. No client, Maven or
semantic-version release is created by this backend-only update.

Deployed to `5.83.145.152` on 2026-09-26 at 16:33 UTC. The running container's
image/revision matched the tag/commit above. Local `/ready` passed; public
`/health` and `/ready` returned 200; WebSocket protocol 1.1 returned `hello_ok`
with `groups`. Other running containers retained identical IDs and start times.
Only MCVoice's image pin/container changed; nginx and firewall configuration
were untouched. `.env` remains mode 600; its protected backup is
`/opt/mcvoice/.env.bak-routing-20260926T163317Z`. Rollback tag: `0.1.2`.

[Recorded run data](benchmarks/rust-routing-2026-09-26.json) includes every initial
and repeated run, CPU observations, counters and diagnostic histograms. Reproduction commands
and instrumentation details are in [the load-test README](../tools/load-test/README.md).
