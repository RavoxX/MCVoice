# Simple Voice Chat interoperability

MCVoice can talk to players who use the ordinary **Simple Voice Chat** (SVC)
mod on servers that run the SVC server plugin/mod. It does so with an
independent implementation in `client/svc-compat`. The SVC client mod is not
needed, and no SVC source code, assets, icons or sounds are included.

## Modes

| Server | Our users talk to each other via | SVC-only users reachable | Transport label |
|---|---|---|---|
| no SVC | MCVoice cloud | - | `Cloud Voice` |
| SVC, interop OK | MCVoice cloud | yes, via SVC interop | `Hybrid: Cloud + SVC` |
| SVC, interop failed | MCVoice cloud | **no** (status says so) | `Cloud Voice` + SVC status line |
| SVC, cloud down | SVC interop | yes | `Simple Voice Chat Interop` |

The microphone is Opus-encoded **once**, and the same frames go to both
transports.

## Detection (behaviour-based only)

1. After joining, the platform registers our clientbound SVC channels
   (`minecraft:register` / legacy `REGISTER`).
2. SVC is *advertised* only when the server announces that it accepts
   `voicechat:request_secret`.
3. The client sends `request_secret` with a compatibility version from
   `SvcProtocols.CANDIDATES` (newest first, at most `MAX_ATTEMPTS`, 2.5 s each).
4. SVC is *confirmed* only by a well-formed `voicechat:secret`.

MOTD text, server names and brands are never used. Servers without SVC never
receive a single SVC plugin message from us.

## Voice session (UDP)

`transport/SvcUdpClient`: authenticate, then connection check, then connected.
It answers keep-alives and pings, sends microphone packets, and receives
player-sound packets. Encryption uses the per-player secret from the
handshake. Two candidate AES constructions are implemented
(`SvcCipher.Mode`); the one the server acknowledges during authentication is
locked in. Location sounds are received but currently ignored, and group
audio is not supported yet.

## Versioning and failure

* Protocol generations live in `protocol/vN` (`SvcProtocolV1` today); the
  registry maps compatibility versions to them.
* Unknown or unanswered versions end in `INCOMPATIBLE` with a diagnostic such as
  `Simple Voice Chat detected but it did not answer compatibility versions 20, 19, 18; SVC-only players are not reachable`.
* Malformed data ends in `FAILED` with a diagnostic; parsing is bounds-checked
  and exceptions never reach the game.
* Cloud voice keeps working in every SVC failure state.

## Deduplication

Another MCVoice user in hybrid mode reaches you twice: over the cloud and over
SVC. `TransportSelector` keeps exactly one stream per speaker UUID; see
[`protocol/specification/dedup-state-machine.md`](../protocol/specification/dedup-state-machine.md).
In short: prefer cloud for speakers that the backend's `presence` marks as
MCVoice peers while the cloud link is healthy. Fall back to SVC after 200 ms
of cloud silence while SVC is delivering. Frames from the losing transport are
dropped before decoding. Either transport's frames still pass the local entity
check first.

## Verification status

| Check | Status |
|---|---|
| Unit tests vs in-process fake SVC server (both cipher modes, fail-safe paths) | automated, passing |
| Headless 3-player hybrid test (A, B MCVoice + C SVC-only through a fake SVC relay; no duplicates) | automated, passing |
| Real SVC server plugin handshake + UDP auth (`.github/workflows/svc-interop.yml`) | see the latest run; the compatibility versions confirmed there are recorded in `SvcProtocols.VERIFIED` and in the release report |
| Two real Minecraft clients (one with the SVC mod) | manual test plan below |

The fake-server tests only prove that our side is self-consistent. The real
compatibility claim comes from the CI probe against the unmodified SVC server,
and from the manual test.

## Manual integration test (full Minecraft instances)

1. Paper server with the SVC plugin, `online-mode=true`.
2. Player C: vanilla launcher + the Simple Voice Chat mod.
3. Players A and B: MCVoice (same Minecraft version), backend configured.
4. Expected on A's status screen (`OPEN_DEBUG` key): `SVC: CONNECTED (detected, compatibility N, ...)`
   and `Transport: Hybrid: Cloud + SVC`.
5. A speaks: B and C hear A exactly once. C speaks: A and B hear C. B speaks:
   A hears B once (debug line `duplicates suppressed` increases).
6. Stop the backend: A and B keep hearing each other through SVC after about
   200 ms, with the status showing `Simple Voice Chat Interop`.
7. Walk out of range or change dimension: audio stops within 20 ms.
