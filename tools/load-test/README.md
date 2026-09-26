# MCVoice load test

Simulates many MCVoice clients without Minecraft and drives either backend.
Each client authenticates (offline mode), announces a scope, moves, reports
visible peers (its proximity group), and - for a configurable fraction of
players - sends 50 Hz voice frames. Players randomly change dimension, switch
"proxy sub-server" (new population) and disconnect/reconnect.

Voice payloads carry the send timestamp, so the tool reports true end-to-end
relay latency (client -> relay -> client, same host clock).

## Reproducible benchmark

```sh
# 1. start a backend in offline mode (development only)
cd backend/go && AUTH_MODE=offline RATE_LIMIT_CONNECT_PER_MIN=0 go run ./cmd/mcvoice-backend
#    or: cd backend/rust && AUTH_MODE=offline RATE_LIMIT_CONNECT_PER_MIN=0 cargo run --release

# 2. run the load test (same machine)
cd tools/load-test
go run . -url ws://127.0.0.1:8080/v1/control -clients 500 -group 10 -talkers 0.3 -duration 60s -json result.json
```

`tools/load-test/run-both.sh` builds both backends and runs the identical
scenario against each, printing a side-by-side summary.

Reported fields: `delivery_ratio` (relays received / relays expected within the
proximity groups; slightly below 1 right after moves because the backend
routes on the latest reported visibility), `latency_ms_p50/p95/p99`,
`sent_pps`, `relayed_pps`, `cross_scope_deliveries` (frames received from a
player currently in another scope; only expected during the brief settle
window after a simulated switch).

The number of talkers is exactly `floor(clients * talkers)`, distributed using
`-seed` (default 1). Movement is seeded too; UUIDs and cryptographic keys remain
random. `-peer-refresh 1s` adds periodic full visibility updates to exercise
the control write path. Positions update at 5 Hz and voice at 50 Hz. The actual
`sent_pps` reveals if the generator could sustain that rate.

Latency samples cover every sixteenth received relay throughout the run,
instead of stopping after the first two million samples. Delivery counts
include every relay. Receivers start on each connection (including reconnects)
and drain for 300 ms after sending stops. Long backlogs can exceed that drain;
interpret saturated runs together with their delivery ratio. For stable
throughput comparisons, disable churn and scope changes; exercise those
separately with conformance and churn tests.

## Comparing Rust revisions

Save a release binary before editing, then build and save the changed binary.
Use the **same load generator** for both. `compare-rust.py` only targets loopback
and starts a fresh backend for each run:

```sh
go build -o ../../release-output/loadtest .
python3 compare-rust.py \
  --backend before=/absolute/path/to/before \
  --backend after=/absolute/path/to/after \
  --loadtest ../../release-output/loadtest \
  --out ../../release-output/routing-comparison \
  --cases 500:10,500:25,500:50,1000:10 --duration 20s --repeats 3
```

The runner uses 30% talkers, a 5-second ramp, visibility refreshes every second,
no churn/scope changes, and four UDP/Tokio workers (override with `--workers`).
It alternates binary order between repeats and records client summaries,
backend metrics/logs, and backend CPU time from `wait4`. CPU includes startup,
ramp, measurement, drain and shutdown; 100% means one core. Run with other
builds and benchmarks idle. Client and server still compete for the same host,
so these results are not a production capacity estimate.

For diagnostic runs, `profile-rust.py /path/to/new/directory` copies the current
Rust sources and injects Hub wait/hold timers and a relay-scoped allocation
counter into that disposable copy. Build using the printed command and run it
with the same runner. Shutdown logs contain `PROFILE` JSON: count, total and
maximum nanoseconds, power-of-two histograms, and allocations/reallocations
inside `relay_voice`. Histogram bucket `i` covers `[2^(i-1), 2^i)` ns (bucket 0
is zero, the last bucket includes overflow). The profiler needs Rust 1.79+
inline const support, covered by this backend's toolchain requirement.

Profiling adds clocks and atomics; compare its diagnostics separately and never
deploy the instrumented binary. Use uninstrumented binaries for latency/CPU
comparisons. See [the routing optimization report](../../docs/rust-routing-performance.md)
for the recorded measurements and their limits.
