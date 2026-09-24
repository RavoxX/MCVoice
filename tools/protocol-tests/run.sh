#!/usr/bin/env bash
# Cross-implementation protocol conformance:
#  1. shared vectors are up to date (Python reference generator)
#  2. Go, Rust and Java implementations pass the vectors (their unit tests)
#  3. the black-box behaviour suite passes against BOTH backend binaries
#  4. the Java client end-to-end tests pass against BOTH backend binaries
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-$ROOT/release-output/protocol-tests}
mkdir -p "$OUT"

echo "== vectors up to date"
python3 "$ROOT/protocol/test-vectors/generate.py" --check

echo "== build backends"
(cd "$ROOT/backend/go" && go build -o "$OUT/backend-go" ./cmd/mcvoice-backend && go build -o "$OUT/conformance" ./cmd/mcvoice-conformance)
(cd "$ROOT/backend/rust" && cargo build --release -q && cp target/release/mcvoice-backend "$OUT/backend-rust")

echo "== vector tests"
(cd "$ROOT/backend/go" && go test ./internal/protocol ./internal/routing)
(cd "$ROOT/backend/rust" && cargo test --release -q --test vectors)
(cd "$ROOT/client" && ./gradlew -q :common:test :network:test)

for impl in go rust; do
  echo "== behaviour suite vs $impl"
  "$OUT/conformance" -name "$impl" -- "$OUT/backend-$impl" | tee "$OUT/conformance-$impl.txt"
  echo "== Java client end-to-end vs $impl"
  (cd "$ROOT/client" && MCVOICE_BACKEND_BIN="$OUT/backend-$impl" ./gradlew -q :core:test --rerun)
done
echo "all protocol conformance checks passed"
