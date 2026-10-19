# Input, microphone controls and performance follow-up

This work follows 0.1.8. It does not establish 100,000-user capacity.

## Client behavior

Minecraft 26.3 text fields now explicitly own SDL text input while focused.
See [the input investigation](minecraft-26.3-input.md). Hold the whisper binding
(B by default) to transmit nearby in whisper mode without also holding V.
Whisper range, local entity validation, current-world checks and transport
selection still apply to every positional frame.

Groups have an independent `groupActivationMode`: `VOICE_ACTIVATION` by default
(including old configurations), or `PUSH_TO_TALK` using the normal PTT binding.
The Group Mic button changes this without leaving the group. Whisper alone
does not open a group configured for PTT. Group delivery remains centered and
restricted to current membership. Mute, deafen and leaving the world gate both
channels; proximity keeps its own activation mode.

Control sends now enqueue into a 64-message FIFO belonging to one connection.
Overflow aborts that connection and reconnects rather than dropping a peer delta
or replaying commands into another session. Handshake, DNS, authentication and
WebSocket writes run on network workers. Disconnect closes the raw socket,
bypassing both the write lock and TLS close-notify writes. UDP audio writes
remain on capture/network threads.

Audio start/stop requests are coalesced by a lifecycle worker. Game ticks never
join audio threads or close audio devices. Stop gates old capture immediately;
the worker closes devices and joins both old threads before starting the latest
requested pair. A device driver that never returns can prevent audio restarting,
but cannot cause overlapping encoder/mixer owners or freeze a game tick. Audio
remains memory-only and is never recorded by these benchmarks.

Tracking enumerates every current local player on every tick. Immutable player
entries and maps are shared only while unchanged. Movement, name changes,
despawns and epoch changes publish fresh immutable state; an old snapshot is
never modified. Dimension normalization is cached. Peer-report scans are gated
before allocating/traversing, with resync and new epochs bypassing that gate.

The mixer visits active streams, reaps idle decoder state periodically, validates
before decoding, skips decoding/mixing rejected frames, and uses a constant-gain
mix path. Rejection is immediately silent; old PCM cannot leak through a fade.
Loss concealment preserves the last frame's group/whisper mode. Every accepted
positional frame still passes PlaybackValidator using the newest local snapshot.
The existing Java Opus decoder remains allocation-heavy for audible speakers.

## Backend investigation

Both backends compute stable presence using shared hub access. A changed diff is
recomputed under exclusive access before being committed, preserving epoch,
verification, scope and mutual-visibility checks if control state changed while
waiting. Sorting, JSON and sending occur after releasing the lock. Stable Go
presence no longer constructs a new UUID set per session. Group expiry similarly
uses a read pass and rechecks expiry under the write lock. Go jitter diagnostics
snapshot session references before touching UDP state and skip busy UDP locks;
if every sample is busy, the previous gauge is retained rather than claiming zero.

Packet forwarding was inspected: candidate selection uses the visible UUID index;
recipient sessions and epochs are captured under hub access, and encryption and
socket writes happen after releasing it. Rust already reuses target/datagram
buffers. The routing predicates and security limits are unchanged. Neither
implementation gains a distributed state model from these changes.

## Reproduction and measurement

Baseline source: `c8dc4061ead14845af9fc736e99b3cde59cc7daf`.
Saved release binaries and Java classes were measured before production edits.
`tools/load-test/ClientBenchmark.java` runs with the core test fixture, synthetic
sine-wave Opus and ThreadMXBean allocation counts. Compile production/test code
with Java 8 release compatibility, then compile the benchmark on the measurement
JDK alongside those classes. Each result is one fresh JVM, with explicit warmup.
`BenchmarkPresenceStable` measures 100/1,000 sessions in ten-player visibility
groups. The portable `compare-rust.py` also runs Go on Windows, using the same
load generator, seeds, four workers, 30% talkers, 1-second visibility refresh,
5-second ramp and no churn. Only loopback endpoints are supported by the runner.

Measurements and validation evidence are recorded below after final checks.

## Practical path toward 100,000 concurrent users

100,000 is a design target, not a supported or measured capacity. These local
tests use at most 200 clients; client/server share one host and clock. The Go
baseline already saturates badly in the 50-player visibility case. Delivery
ratio and achieved send rate must accompany latency: low latency with dropped
or unsent packets is not success.

The current single-process hub remains the main coordination point. Presence
and group expiry still scan sessions; churn and broad visibility can hold read
access long enough to delay writers. Control queues, per-session decoder/key
state, 50-Hz voice, recipient encryption and UDP fanout impose CPU, memory and
bandwidth costs. One Rust worker still sends its outgoing fanout sequentially.
There is no coordinated ownership, revocation or group state across instances.
Increasing connection, visibility or queue limits does not solve these costs.

1. Establish per-shard SLOs for p95/p99 relay latency, delivery, control-update
   age, reconnects, memory per session and maximum lock hold/wait. Test quiet
   presence, continuous speech, dense populations, churn, groups and attack-rate
   rejection independently. Add instrumented lock histograms in disposable
   profiling builds; keep instrumentation overhead out of throughput comparisons.
2. Run separate load-generator hosts and step through 1k, 5k and 10k users per
   shard. Fix sustained loss/backlogs before increasing load. Measure CPU,
   allocations, RSS, packet rate, egress and headroom, including node failure
   and reconnect storms. Profile event-driven dirty presence/expiry scheduling
   and bounded lock batches rather than globally sweeping on every interval.
3. Assign authenticated sessions to authoritative regional shards with sticky
   WebSocket/UDP ownership. Partition by authenticated session/visibility data,
   never the joined address (`network_id`). Preserve current world, optional
   attested sub-server, epoch and mutual-visibility checks on every delivery.
   Local client entity validation remains mandatory even with shared state.
4. Introduce versioned shared presence/group metadata and explicit session/key
   ownership. Cross-shard groups keep their 15-member bound and require current
   membership, removal/revocation ordering and fail-closed behavior on stale or
   unavailable state. Redis can coordinate metadata; it is not an audio broker
   or a substitute for current routing checks. Prove these contracts under
   disconnect, transfer, split-brain and delayed-message tests before rollout.
5. Profile OS packet batching and multi-queue/reuse-port designs against the
   simpler relay; keep replay, AEAD, limiter, administrative mute and dedup checks
   unchanged. At 100k users, 30% speaking at 50 Hz means 1.5M incoming frames/s;
   average fanout 9 means 13.5M outgoing packets/s before other overhead. Use
   measured payload/wire sizes to provision regional bandwidth and redundancy.
6. Reach 100k across tested shards only after distributed load/soak and failover
   runs meet the SLOs with headroom. Publish observed conditions and limits,
   not a general capacity claim from a synthetic microbenchmark.
