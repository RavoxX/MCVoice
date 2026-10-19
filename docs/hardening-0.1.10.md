# MCVoice 0.1.10 hardening

This follow-up addresses the supplied bug report against 0.1.9. Release and
deployment results are recorded after their workflows complete. Existing local
entity, range, world/epoch, mutual visibility, group membership, mute, AEAD and
replay checks remain mandatory.

## Security and compatibility decisions

* Java replay ages use unsigned comparisons. Duplicate current/previous key
  rotations preserve replay state and send counters; invalid IDs, conflicting
  keys and exhausted counters close/reject safely. Fresh byte-ID wrap remains
  valid. SVC mode negotiation commits only on an exact AUTH_ACK. Legacy CBC is
  still unauthenticated; this change fixes premature negotiation, not CBC's
  cryptographic limitations. Only GCM compatibility 20 has real-server coverage.
* Proxy headers are accepted only from explicitly configured
  `TRUSTED_PROXY_CIDRS`. Addresses are parsed and canonicalized; XFF is traversed
  from the trusted peer toward the first untrusted hop. Invalid/oversized chains
  fall back to the peer. Enabling trust without CIDRs fails startup. Do not use
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
