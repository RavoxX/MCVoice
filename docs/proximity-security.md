# Proximity security: why coordinates are never enough

MCVoice decides who can hear whom **without any Minecraft server plugin**.
The hard part is proxy networks (Velocity, BungeeCord, Waterfall): many
sub-servers share one public address, each with its own worlds that use the
same dimension names and overlapping coordinates.

```
Player A: play.example.org -> survival-1, minecraft:overworld, (100, 64, 100)
Player B: play.example.org -> survival-2, minecraft:overworld, (100, 64, 100)
```

A and B must never hear each other positionally (unless they chose to join
the same [voice group](#voice-groups)). Nothing that A and B *report* can reliably
distinguish their sub-servers: the address, dimension name and coordinates are
identical, and the client cannot learn the real sub-server name behind a proxy.

## The rule

> **If the speaker does not currently exist as a tracked player entity in my
> current local Minecraft world, positional audio from that speaker is not
> played.**

The Minecraft server only sends a client the entities that are actually near
it in *its* world. Survival-2's players are never sent to a client on
survival-1. So the local entity tracker is ground truth that nobody on the
voice path can forge on the listener's behalf.

## Defence in depth

| Layer | Where | What it prevents |
|---|---|---|
| 1. Scope | backend | Routing between different dimension names (`world_id`), or different sub-servers when both players carry a companion-plugin attestation. The address a player joined through (`network_id`) is **not** compared: one server has many addresses (aliases, IPs, tunnels, several proxies), and players who joined through different ones must still hear each other. |
| 2. World-session epoch | client + backend | Audio from before a server switch, dimension change, respawn or reconnect. Every such event creates a new epoch; the backend clears position + visibility atomically; relayed frames carry the **recipient's** epoch and the client drops mismatches. |
| 3. Mutual visibility | backend | A frame is only routed to R if **R reported the sender's UUID** as a locally tracked player **and the sender reported R** (mandatory). UUIDs are Mojang-verified and a player is on one server at a time, so this ties routing to the actual game, whatever address each player used. A player on another server or sub-server is never in R's report. |
| 4. Backend distance | backend | Bandwidth: no routing beyond range + slack (4 blocks for latency). |
| 5. **Local entity check** | receiving client | Final authority for positional audio, runs for every positional frame of every transport (cloud *and* Simple Voice Chat): tracked entity exists, same world, locally computed distance ≤ range, not muted, not deafened. Group frames (mode 2) are the only frames it does not apply to; see [voice groups](#voice-groups). |

Layers 1-4 are *hints* from possibly-lying clients: a modified client can
report fake visibility or positions. They reduce accidental routing and
bandwidth. They are **not** an anti-cheat boundary. Layer 5 is what protects
an honest listener from positional audio, and it cannot be influenced by the
backend or by other players. Group audio is different: whether it plays
depends on the member list the backend sends, so for groups the backend is
trusted (it is anyway the party that authenticates players and relays all
cloud audio).

## Implementation map

* `client/common/.../proximity/WorldTracker.java`: builds an immutable
  `WorldSnapshot` every client tick from the adapters, and detects
  world-session changes. The checks, in order: server address changed, local
  player entity id changed (JoinGame, which is also a proxy switch), dimension
  changed, world object replaced. Platform hooks (`VoiceClient.invalidateWorld`)
  publish an empty snapshot *immediately* on respawn/disconnect/transfer,
  without waiting for the next tick.
* `client/common/.../proximity/PlaybackValidator.java`: the rule above, and
  for group frames (mode 2) the membership check instead.
* `client/audio/.../SpatialMixer.java`: re-runs the check **every 20 ms frame**
  against the newest snapshot, so a disappearing entity silences its stream
  within one frame (with a ≤ 20 ms fade instead of a pop). Panning and
  attenuation use only local entity positions; group frames are mixed centred
  at the player's volume, without position.
* `backend/*/routing`: checks 1-4 and group delivery (spec 8.1) as a pure
  function, verified by shared vectors (`protocol/test-vectors/routing.json`).

## Tests that pin this down

| Requirement | Test |
|---|---|
| 10 blocks, range 48 → accepted | `playback.json: distance_10_range_48`, `ProximityScenarioTest.distance10Accepted`, conformance `distance_10_blocks_delivered_both_ways` |
| 60 blocks → rejected | `distance_60_range_48`, conformance `distance_60_blocks_not_delivered` |
| same coordinates, other dimension → rejected | `same_coords_other_dimension`, conformance `different_dimension_same_coordinates_not_delivered` |
| same coordinates, same dimension, not tracked → rejected | `not_tracked_same_coords`, `ProximityScenarioTest.sameCoordinatesSameDimensionButNotTrackedRejected` |
| same server joined through two different addresses, both see each other → delivered | `routing.json: different_address_same_server`, conformance `different_address_same_server_delivered_both_ways` |
| same proxy address + coordinates + dimension name, other population → rejected | `ProximityScenarioTest.proxySubserverDifferentPopulationRejected`, conformance `proxy_subserver_same_coordinates_not_visible_not_delivered`, `attested_subservers_are_isolated`, `EndToEndTest` (sub-server switch) |
| frame from old epoch after switch → rejected | `old_epoch_after_switch`, conformance `stale_epoch_packet_dropped`, `recipient_epoch_is_current` |
| entity removed → next packet rejected | `ProximityScenarioTest.senderEntityRemovedNextPacketRejected`, conformance `disconnect_removes_peer` |
| group frame from a non-member → rejected; positional frame from a group member still needs the entity | `playback.json: group_frame_not_member`, `positional_frame_group_member_still_needs_entity` |
| group audio across servers and worlds, never to outsiders | `routing.json: group_only_across_worlds`, conformance `group_voice_across_servers_not_to_outsiders`, `EndToEndTest.voiceGroupAcrossServers` |
| group member nearby hears a frame once | `routing.json: group_and_proximity_no_double`, conformance `group_and_proximity_no_double_delivery` |
| membership ends on disconnect / 10 s out of a world | conformance `group_membership_ends_on_disconnect_and_out_of_world` |

## Voice groups

Group audio (spec 8.1, 9.1) is the one deliberate exception to positional
playback: players who joined the same group chose to hear each other
anywhere. It is played **centred, not positional**, and only if the sender is
a member of the listener's current group according to the backend's member
list (`group_joined`/`group_update`). Every positional frame still has to
pass the local entity rule, also from group members. Membership needs an
authenticated session that is in a world, ends on disconnect or after 10 s
out of a world, and is capped at 15; passwords are hashed in memory and
wrong guesses are rate limited. The group list shows only codes, member
counts and whether a password is needed; names are sent to members only.

## Known limits (stated honestly)

* Tracking range limits voice range: if a server tracks players only within
  32 blocks (`entity-tracking-range`), voice stops at 32 blocks even when the
  voice range is 48. This is the price of the local-entity rule.
* Invisible/vanished players who are not sent to the client are not audible,
  which is intended.
* In a voice group, every member hears you wherever they are while your
  microphone is on (voice activity decides when). Mute yourself or leave the
  group to stop that; anyone who knows the code can join an open group.
* A malicious *speaker* can still be heard by nearby honest listeners, as in
  any voice chat. Moderation (bans, mutes) is backend-side, with an optional
  companion plugin for server-verified identity.
