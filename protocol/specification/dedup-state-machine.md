# Transport selection and deduplication state machine

Normative for the Java client (`client/common/.../transport/TransportSelector.java`).
Test vectors: [`protocol/test-vectors/dedup.json`](../test-vectors/dedup.json).

## Goal

A speaker may reach the local client through

* **CLOUD** – the MCVoice backend (other MCVoice users), and/or
* **SVC** – our own Simple Voice Chat compatibility session (any SVC-speaking
  client, including other MCVoice users running in hybrid mode).

The local user must hear **at most one stream per speaker at any time**.
Deduplication is keyed by the speaker's Minecraft UUID. Audio content is never
compared.

## Inputs (per speaker UUID `u`)

| input | source |
|-------|--------|
| `cloudLinkHealthy` | global: control connected ∧ `udp_ok` received ∧ last PONG/relay < 15 s |
| `cloudPeer(u)` | `u` is in the latest `presence` set from the backend |
| `lastCloud(u)` | arrival time of the last CLOUD frame for `u` that passed the playback check |
| `lastSvc(u)` | arrival time of the last SVC frame for `u` that passed the playback check |
| `svcSince(u)` | arrival time of the first SVC frame of the current SVC burst |

Constants: `STALL_MS = 300` (a transport is *fresh* if it delivered a frame in the
last 300 ms), `FALLBACK_MS = 200` (how long SVC must be delivering while cloud is
silent before falling back).

Derived:

```
cloudOk    = cloudLinkHealthy ∧ cloudPeer(u)
cloudFresh = now − lastCloud(u) ≤ STALL_MS
svcFresh   = now − lastSvc(u)   ≤ STALL_MS
```

## States

```
            ┌──────────── cloud frame ─────────────┐
            ▼                                       │
  NONE ──► CLOUD ◄──── cloud frame (resume) ──── SVC_FALLBACK
    │        │                                      ▲
    │        └── cloud silent ≥ FALLBACK_MS while ──┘
    │            SVC fresh (cloud stalled)
    │
    └──► SVC   (speaker is not a cloud peer, or cloud link down)
```

* `NONE` – nothing selected (speaker idle).
* `CLOUD` – CLOUD frames are played, SVC frames for `u` are dropped *before
  decoding* (counted as `suppressed_duplicates`).
* `SVC` – speaker is an ordinary SVC user (or cloud unusable); SVC frames played.
* `SVC_FALLBACK` – speaker is a cloud peer, but cloud frames stopped arriving
  while SVC frames keep coming; SVC is played until cloud frames resume.

## Decision (evaluated for every arriving frame, after the playback check)

```
select(u, now):
  if cloudOk:
      if cloudFresh:                                   return CLOUD
      if svcFresh and now − svcSince(u) ≥ FALLBACK_MS
                  and lastCloud(u) < svcSince(u):      return SVC_FALLBACK
      return CLOUD            # wait for cloud; SVC suppressed during the grace period
  else:
      if svcFresh:                                     return SVC
      if cloudFresh:                                   return CLOUD
      return NONE
```

A frame is played iff its transport matches the selected state
(`SVC_FALLBACK` and `SVC` both play SVC frames).

The arriving frame's own timestamp is applied *before* the decision (a cloud frame
arriving makes `cloudFresh` true, so a returning cloud stream immediately wins).

## Switch-over

When the selected transport for `u` changes:

1. the old transport's jitter buffer for `u` is flushed,
2. the new stream starts with a 20 ms fade-in,
3. the Opus decoder of the new transport is reset (packet-loss concealment
   covers the gap).

This guarantees there is never more than one decoded stream per speaker in the
mixer.

## Edge cases

| situation | result |
|-----------|--------|
| MCVoice peer, cloud healthy, SVC also delivering | CLOUD only, SVC dropped |
| MCVoice peer, first SVC frame arrives before the first cloud frame | SVC suppressed for up to `FALLBACK_MS`; cloud frame arrives → CLOUD |
| MCVoice peer, cloud link drops (`cloudLinkHealthy=false`) | next SVC frame → SVC |
| MCVoice peer, cloud relay for this speaker stalls (link otherwise up) | after `FALLBACK_MS` → SVC_FALLBACK |
| cloud frames resume | CLOUD (switch with fade) |
| SVC-only speaker (never in presence) | SVC |
| MCVoice-only speaker on a server without SVC | CLOUD |
| speaker leaves presence while talking via cloud | cloudOk false; cloud still fresh and SVC not → CLOUD continues |
| playback check fails (entity gone, other world, out of range, old epoch) | frame dropped before selection; no state change |
