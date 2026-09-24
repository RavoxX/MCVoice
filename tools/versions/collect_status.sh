#!/usr/bin/env bash
# Merge the per-loader CI results published by mc-build (tooling/probe-output:builds/*/*.json)
# into versions/build-status.json and re-render versions/supported.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP=$(mktemp -d)
git -C "$ROOT" fetch -q origin tooling/probe-output
git -C "$ROOT" archive FETCH_HEAD builds | tar -x -C "$TMP"
python3 "$ROOT/tools/versions/matrix.py" merge "$TMP/builds"
python3 "$ROOT/tools/versions/matrix.py" validate
python3 "$ROOT/tools/versions/matrix.py" render
rm -rf "$TMP"
