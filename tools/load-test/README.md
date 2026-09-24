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
