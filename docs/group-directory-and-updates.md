# Group directory and update notifications (0.1.11, unreleased)

When outside a group, **G** lists each group's code, the first two member names
(followed by `, ...` for additional members), its member heads, count and password
status. Hovering a head reveals its name. The entire row still joins the group;
password handling is unchanged. Existing platform drawing uses known vanilla
skins; unknown profiles use vanilla's default head without external skin URLs
or blocking profile requests. Rosters refresh with the first page every ten
seconds, by Refresh, or on a new search/filter. Deeper pages remain stable while
browsing; scrolling loads more twenty-entry pages.

**Show: All Groups** is the default. Switching to **This Server** persists
`groupServerOnly` in `config/mcvoice.json` without restarting audio. A group is
included if any currently in-world member joined through the same normalized
domain and port as the viewer. Case, the DNS root dot and omitted/default 25565
are normalized. Aliases, IPs, different ports and SRV targets are not resolved
into one identity. Players must use the same address. Mixed-server groups
appear for each represented address. Missing addresses (for example some Realms
connections) cannot use this filter; choose All Groups instead.

The new `group_directory` backend capability adds optional `details` and
`server_only` fields to paged group requests. Legacy clients keep their original
metadata-only replies. An old backend remains usable, but cannot provide heads
or the server filter; filtering fails visibly rather than showing global
results under a local label. UUID/name rosters are public, including protected
groups. No IPs, raw domains, coordinates, passwords or credentials are returned.
This filter uses self-reported scope and is not proof of server membership.
It never changes audio authorization, visibility/range checks or group routing.
Both implementations copy rosters only for the selected page, outside of which
no roster allocation is added. Existing frame, page and request-rate bounds stay.

## Updates

From this client onward, joining a world starts one background check per client
process when **Settings > Updates > Update checks** is on (default). Requests
only read the [public GitHub releases API](https://docs.github.com/en/rest/releases/releases#list-releases)
without authentication. No Minecraft
identity, access token, server address or voice data is sent. The request still
uses an ordinary HTTPS connection to GitHub. The checker scans at most ten
100-release pages, with bounded response sizes/time and a separate daemon worker
so voice control/heartbeats and gameplay do not wait for GitHub.

Only a numerically newer stable `v<version>-mc<current-minecraft>` release with
an uploaded JAR for the exact current loader qualifies. Development versions,
drafts, prereleases, other Minecraft versions and missing loader assets do not
trigger notices. Links are constructed for the fixed RavoxX/MCVoice repository,
never trusted from remote `html_url` fields. The short in-game notice directs
players to Settings > Updates, where the release URL is readable and the
**Open ... on GitHub** button opens the system browser. If browser integration
is unavailable, the displayed URL remains usable. Nothing is auto-downloaded
or installed. Offline/API-limit/malformed results remain quiet and never claim
the client is up to date. An in-flight request may finish after disabling checks;
its result is hidden. Older installed clients cannot receive this new UI.

## Verification

Regression tests cover rosters/hover/joins, filter persistence and stale pages,
scope changes and legacy browsing, Java-to-backend previews/filtering, protected
group metadata, absent scopes, strict protocol fields, version comparison,
exact game/loader matching, multi-page searches, missing/malformed/API failures,
link host pinning, preferences and small-GUI layout. The 26.3 startup harness
also exercises native SDL events for the directory filter and update link.

CI/runtime results are recorded after validation. This document does not claim
a release, supported-version rebuild or production backend deployment.
