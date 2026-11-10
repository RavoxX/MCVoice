# Rust backend review fixes (0.1.16)

A read-through of `backend/rust` found no routing, encryption or replay
defect. It did find the following bugs and hardening gaps, all fixed in 0.1.16.
The wire protocol is unchanged and the client code is unchanged.
The Go backend was not part of this review; where it differs, that is stated.

## Bugs fixed

| Problem | Fix |
|---|---|
| A session closed right after registration (replaced by a newer login, or banned) was never told: the close flag was not stored while no task was subscribed yet, and a new subscriber does not see earlier changes. The old connection stayed open until the client hung up. | The flag is always stored and every task waits for the flag's value, not for a change notification. |
| Voice frames dropped for an un-negotiated FEC flag were not counted: `unnegotiated_fec` was missing from the metric's reason list. | The reason is registered. It is a Rust-only label of `mcvoice_packets_dropped_total`. |
| `BANS_FILE` changes were ignored unless the file's modification time was strictly newer than the last load (two edits within one timestamp tick, or a restored older copy). | The file is compared by SHA-256 of its content. It is read off the async worker threads. |
| A session-server outage counted as a failed login against the address and could lock players out. | Only real rejections count. The client still receives `auth_failed`, with a message that says the session server is unavailable. |
| `NaN`/`inf` ranges and rates, `SESSION_TIMEOUT_SECONDS=0`, `MAX_SESSIONS=0` and overflowing durations passed validation. | Start-up fails with a message naming the variable. Durations are limited to one year. |
| `mcvoice_active_voice_sessions` could drift upward when a UDP hello raced with the end of its session. | A session that left the hub is marked and ignores late datagrams. |
| `dash_uuid` could panic on a 32-byte non-ASCII id in a session-server response. | Non-ASCII ids are rejected without slicing. |

## Hardening

* **Resume tokens no longer renew forever.** Tokens carry `auth_time`, the
  time of the last real authentication, and it is copied on every renewal. A
  resume is refused once `RESUME_MAX_LIFETIME_SECONDS` (default 86400) have
  passed since then; the client then authenticates with Mojang again, as it
  already does for any rejected resume. Tokens issued by 0.1.15 or by the Go
  backend have no `auth_time` and are bounded by their `iat`.
  The Go backend ignores the claim and does not enforce this limit.
* **UDP `HELLO` and `PING` are rate limited** per session (10 per second,
  burst 40), because each one is answered. Excess datagrams count as
  `rate_limited` drops. Voice keeps its own budget.
* **Pre-authentication slots.** The global limit is 1024 (was 256), and one
  address may hold at most `max(4, RATE_LIMIT_CONNECT_PER_MIN / 4)`
  unauthenticated connections at once (7 by default; unlimited when the connect
  limit is disabled). Over the limit the upgrade gets HTTP 429.
* **IPv6 clients share per-address limits per /64**, for the connect,
  authentication-failure and pending-connection limits. Log tags are derived
  from the same /64 key.
* **`panic = "abort"`** in the release profile: a panic ends the process so
  the supervisor restarts it, instead of leaving poisoned locks or a silently
  stopped UDP worker behind a healthy-looking `/health`.

## Verification

* 28 unit tests and 7 vector tests pass; `cargo fmt --check` and
  `cargo clippy --all-targets --release -- -D warnings` are clean.
* The shared conformance suite passes 44/44 against the Rust binary.
* New regression tests cover each bug above and each hardening item except
  the process abort.

## Not changed

Lock contention on the hub (every position update takes the global write
lock), the 500 ms full presence scan and the double JSON parse of control
frames are performance work, not defects. See
`docs/rust-routing-performance.md` for the measured limits.
