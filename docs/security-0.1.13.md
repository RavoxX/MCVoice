# Security boundaries in 0.1.13

This change addresses GitHub issues #17–#21. It changes no MCVoice wire format,
group routing policy or positional playback rule. The Java client remains Java 8.

| Issue | Enforcement boundary | Regression coverage |
|---|---|---|
| #17 | Go replay-window advancement iterates over a bounded distance, including the largest unsigned counter. Authentication still precedes mutation. | Small advances ending at `MaxUint64`, invalid tags, replay/window controls and progress of a subsequent authenticated session. |
| #18 | SVC sends and accepts authenticated AES-128-GCM only. CBC identifiers cannot select an encryption implementation. Controls also require valid lengths and session states. | Captured retry probes contain no CBC authentication credentials; CBC ACKs, malformed controls and invalid tags are rejected; compact and authenticated 256-byte backing-buffer ACKs work. |
| #19 | Go group mutations and peer-state updates require both current connection and UUID-index ownership under the hub lock. | Replacement and unregister followed by stale create/join/leave or peer mutations cannot create ghosts or change the current member. |
| #20 | Every `javap` argument passes Java binary-name/access validation. Archive extraction rejects escapes and symlinks. Inspection has read-only permissions and no persisted checkout credentials; a separate job publishes bounded text data. | A harmless JVM-agent fixture demonstrates the former option-execution path, then proves rejection and normal inspection; traversal and publication-scope checks cover sibling boundaries. |
| #21 | The SVC UDP session remembers exact authenticated 96-bit GCM nonces before activity, replies or audio callbacks. Accepted history never expires on silence, jitter reset or speaker reconnection. | Captured audio stays rejected after actual jitter underrun and after a fresh sequence restart; captured controls cannot refresh activity. Same-key socket replacement retains history. |

## SVC compatibility and resource limits

Real SVC uses the same channel/player UUID after a speaker reconnects and resets
their sequence to zero. Consequently sequence numbers alone cannot distinguish
an old recording of an encrypted packet from new speech. The replay ledger uses
the authenticated GCM nonce instead. Freshly encrypted packets may reuse a sound
sequence, arrive out of order, or use any unsigned sequence value.

The ledger stores packet identities only, never audio, ciphertext, positions or
player names. It has a 65,536-packet bound. At 49,152 accepted packets MCVoice
stops the old UDP session and requests a new secret through the Minecraft plugin
channel. Only a different key starts new history. A server returning the same
key cannot clear the old ledger. If no new key arrives within 60 seconds, SVC
fails closed and asks the user to rejoin; cloud voice remains independent.

This renewal briefly interrupts SVC audio. In the real Paper 1.21.4 / SVC 2.6.24
observation the server ignored secret requests while UDP was connected and
provided a different key about nine seconds after UDP was stopped. At one
50-frame/s incoming stream the renewal threshold is about 16 minutes; several
speakers reach it sooner. Keepalives count too. This is a deliberate compatibility
tradeoff of protecting an existing protocol without numbered control packets or
a receiver-visible sender generation. Future negotiated replay counters/key
epochs would allow constant-memory windows and seamless renewal. They require
server support and are not claimed here. Older unverified CBC-only servers are
unsupported; no downgrade is attempted.

The probe workflow's diagnostics retain their existing `<minecraft>/latest.txt`
path on `tooling/probe-output`. Each published plain-text file is bounded to its
last 400,000 bytes; full step logs remain in GitHub Actions.

## Validation scope

Focused tests cover malicious inputs and legitimate controls. The real-server
interop workflow additionally exercises incoming synthetic speech, a sender
disconnect/rejoin and fresh-key renewal. No SVC source or assets are included;
the unmodified plugin is downloaded only by the CI harness. Full Minecraft
instances and listening to real microphone playback remain separate manual
coverage. Release and deployment results are recorded only after they complete.

Existing scaling limits remain documented in [hardening](hardening-0.1.10.md)
and [input/performance](input-and-performance.md). These fixes do not establish
100,000-user capacity or remove the need for shared-state routing shards and
production load testing.
