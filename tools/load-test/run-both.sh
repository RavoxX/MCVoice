#!/usr/bin/env bash
# Build both backends and run the same load scenario against each.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
CLIENTS=${CLIENTS:-300}
DURATION=${DURATION:-30s}
OUT=${OUT:-$ROOT/release-output/load-test}
mkdir -p "$OUT"
(cd "$ROOT/backend/go" && go build -o "$OUT/backend-go" ./cmd/mcvoice-backend)
(cd "$ROOT/backend/rust" && cargo build --release -q && cp target/release/mcvoice-backend "$OUT/backend-rust")
go build -o "$OUT/loadtest" .
for impl in go rust; do
  PORT=$((18080 + RANDOM % 1000)); UDP=$((24000 + RANDOM % 1000))
  AUTH_MODE=offline CONTROL_PORT=$PORT VOICE_UDP_PORT=$UDP PUBLIC_HOSTNAME=127.0.0.1 LOG_LEVEL=warn \
    RATE_LIMIT_CONNECT_PER_MIN=0 "$OUT/backend-$impl" & PID=$!
  for _ in $(seq 50); do curl -sf "http://127.0.0.1:$PORT/ready" >/dev/null && break; sleep 0.2; done
  echo "== $impl"
  "$OUT/loadtest" -url "ws://127.0.0.1:$PORT/v1/control" -clients "$CLIENTS" -duration "$DURATION" -json "$OUT/$impl.json" | tail -25
  curl -s "http://127.0.0.1:$PORT/metrics" | grep -E '^mcvoice_(packets|bytes|invalid|connected|active)' > "$OUT/$impl.metrics" || true
  kill $PID; wait $PID 2>/dev/null || true
done
python3 - "$OUT" <<'PY'
import json,sys,os
d=sys.argv[1]
rows=[(k,json.load(open(os.path.join(d,f"{k}.json")))) for k in ("go","rust")]
keys=["connected","sent_pps","relayed_pps","delivery_ratio","latency_ms_p50","latency_ms_p95","latency_ms_p99","reconnects","cross_scope_deliveries"]
print("\n| metric | go | rust |\n|---|---|---|")
for k in keys:
    f=lambda v: f"{v:.3f}" if isinstance(v,float) else str(v)
    print(f"| {k} | {f(rows[0][1][k])} | {f(rows[1][1][k])} |")
PY
