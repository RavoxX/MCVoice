# Audio reliability in 0.1.15

This update targets demonstrated gaps rather than adding every proposed feature.
The Java core remains Java 8. No voice is recorded or retained, and positional
playback still requires a currently tracked, in-range local player on every
frame. Group playback still requires current membership and remains centred.

| Proposal | Decision and reason |
|---|---|
| Opus FEC / loss recovery | Implement proximity receive-side FEC: the encoder already enabled it, but missing frames always used concealment. Only adjacent, already buffered packets with negotiated scope-safe metadata may supply redundancy. Groups retain concealment. |
| SVC reconnect / key renewal | Implement automatic retry with bounded backoff, cancelled stale connection attempts, and planned renewal during a speech break where possible. Preserve authenticated GCM and exact same-key replay history. |
| Noise suppression / echo cancellation | Retain the existing gain, high-pass filter, noise gate and optional AGC. A gate is not full denoising or echo cancellation. Reliable AEC needs a timed playback reference and platform/device testing; no new native dependency or unverified DSP is added. |
| Rust scaling / latency | Retain the measured target-buffer reuse, short hub locks, presence diffing and bounded queues. Add only the FEC negotiation needed by this update in both backends. No new load evidence justifies an architectural rewrite or a latency claim. |
| First-time setup | Retain existing device selection, microphone meter/test, native keybindings and startup hints. A new mandatory wizard would duplicate these controls without an identified failure to fix. |
| SVC compatibility | Handle the independently observed `voicechat:remove_state` packet so disconnected voice users disappear. Test real-server reconnection and fresh-key renewal through the production facade. No unverified protocol versions or group audio support is claimed. |
| Adaptive quality | Fix existing adaptive jitter estimation after talk breaks, network resets and duplicate/malformed inputs. Retain VBR and the bounded 2–8-frame startup buffer. Incoming loss is not an uplink measurement, so it does not control the microphone bitrate. No live time stretching is added. |

## Packet recovery and mixed versions

The optional protocol 1.1 capability `opus_fec` enables payload flag `0x04`.
Both backends reject that marker from unnegotiated senders and strip it for
legacy receivers. Ordinary voice, group policies and all routing checks retain
their existing behavior. Updated clients fall back to concealment with older
backends, older senders and SVC packets.

Both backends also clear the marker on every group delivery, including for
capable members. Group membership changes can precede the sender receiving a
roster update, so a capability alone cannot bind redundancy to the current
membership. Group FEC is deferred until an acknowledged generation exists.
The backend enforces this restriction without depending on roster-update timing.
An interrupted 0.1.14 release attempt published some immutable Maven packages;
0.1.15 uses a fresh version and does not replace those artifacts.

This negotiation matters for privacy: a preceding frame might have been a
whisper or private group transmission. The capture encoder resets across
world epochs, group context, transport selection, normal/whisper transitions
and new transmissions. A frame captured across a changed context is discarded.
Receivers require adjacent sequence numbers, the same playback mode and sender
epoch, plus current playback permission, before trying proximity recovery. SVC has no
verified equivalent scope guarantee, so its packets cannot enable FEC.

No extra playout delay is introduced: recovery only uses a following packet
that has already arrived. The following frame is decoded normally once. Opus
falls back to concealment when that packet has no usable redundancy. FEC is
conditional, not a promise to recover every dropped packet. The existing
encoder's automatic codec mode, 5% loss expectation and bitrate are unchanged.
See the official [Opus decoder API](https://opus-codec.org/docs/opus_api-1.5/group__opus__decoder.html)
and [encoder controls](https://opus-codec.org/docs/opus_api-1.5/group__opus__encoderctls.html).

## SVC recovery limits

A failed UDP connection retries after 1, 2, 4, 8, 16 and then at most 30 seconds;
30 seconds of stable connectivity resets the backoff. DNS/socket setup runs on
one daemon worker with one pending attempt; leaving a world, disabling interop
or receiving a newer secret invalidates older attempts. The Minecraft channel
request stays on the game thread; no DNS lookup is performed there. A stalled
JVM resolver may delay the next connection, but cannot publish after cancellation.

Planned renewal becomes eligible at the existing 49,152-packet threshold and
prefers two seconds without incoming or outgoing speech. At 61,440 entries it
proceeds even during continuous speech. The hard 65,536-entry bound, exact nonce
history, GCM-only authentication and fail-closed behavior remain unchanged.
Only a different secret starts fresh history; same-key socket replacement
retains it. A 60-second recovery window is followed by safe automatic retries,
not a silent replay reset. Re-enabling interop works without rejoining.

SVC may wait for the previous UDP session to expire before returning a new
secret. Earlier real-server measurements took about nine seconds, so renewal
still interrupts SVC audio; this update does not claim seamless renewal or
remove the server-side limitation. Cloud voice remains independent.

## Regression coverage and benchmark

Tests exercise actual Opus decoding against a direct decoder reference, reuse
of lookahead without consuming it twice, rejection before decode/speaking,
unmarked legacy/SVC traffic, channel boundaries, duplicates, malformed packets,
talk breaks and resets. Capture tests compare fresh encoder output after each
group, route, whisper and epoch transition. Both backends share an encrypted
UDP conformance case for capable/legacy senders and receivers, including group
conversion and no duplicate proximity delivery. SVC tests cover socket failure,
backoff, retained replay state, late callbacks, fresh-key requirements and
blocked DNS cancellation. The real-server probe also checks departed users.

Run `:audio:lossRecoveryBenchmark` in `client/` for a deterministic synthetic
fixture: 300 frames, 28 isolated losses (9.33%), repeated 20 times after warmup.
It deliberately forces SILK voice mode and a 30% expected-loss setting to
produce measurable redundancy; these are **test settings**, not new defaults.
The reference is an uninterrupted decoder of the same encoded signal.
Recorded lost-frame MSE fell from 23,983,056.28 (concealment) to 4,564,910.62
(FEC), about 81%. This demonstrates recovery on that fixture, not perceptual
speech quality, recovery availability in every codec mode, or a general CPU or
latency improvement. No human voice was used. Timing is JIT-sensitive; raw
measurements are in [the benchmark record](benchmarks/loss-recovery-0.1.15.json).

Stable 0.1.15 is released and the Rust backend is deployed. See
[release and runtime evidence](release-0.1.15.md), the
[byte-verification record](release-0.1.15-verification.json) and
[deployment checks](deployment-0.1.15.md).
100,000 concurrent users remains a scaling target, not proven capacity. Shared
state, routing shards, regional relays and production load/latency measurements
remain necessary; see [the scaling plan](hardening-0.1.10.md) and
[existing Rust measurements](rust-routing-performance.md).
