#!/usr/bin/env bash
# Create or refresh the per-version branch mc/<version>: main + the generated
# minecraft/ build (tools/port-version/port.py). Existing branches are
# updated by merging main (history is never rewritten), then minecraft/ is
# regenerated and committed if it changed.
#
#   tools/port-version/make-branch.sh 1.20.1 [--push]
#   tools/port-version/make-branch.sh --all-supported [--push]   # every version with all loaders PASS
#
# Run from a clean checkout of main. Uses a temporary worktree, so the
# current checkout is left untouched.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
push=0
versions=()
for a in "$@"; do
  case "$a" in
    --push) push=1 ;;
    --all-supported)
      mapfile -t more < <(python3 - <<'PY'
import json
s = json.load(open("versions/build-status.json"))
for mc, loaders in s.get("results", {}).items():
    if loaders and all(v.get("status") == "pass" for v in loaders.values()):
        print(mc)
PY
)
      versions+=("${more[@]}") ;;
    *) versions+=("$a") ;;
  esac
done
[ ${#versions[@]} -gt 0 ] || { echo "usage: $0 <mc-version>... | --all-supported [--push]" >&2; exit 2; }

base=$(git rev-parse HEAD)
git fetch -q origin 'refs/heads/mc/*:refs/remotes/origin/mc/*' 2>/dev/null || true

for mc in "${versions[@]}"; do
  branch="mc/$mc"
  wt=$(mktemp -d)
  if git rev-parse -q --verify "refs/remotes/origin/$branch" >/dev/null; then
    git worktree add -q -B "$branch" "$wt" "origin/$branch"
    (cd "$wt" && git merge -q --no-edit -X theirs "$base" -m "Merge main into $branch")
  else
    git worktree add -q -B "$branch" "$wt" "$base"
  fi
  (
    cd "$wt"
    python3 tools/port-version/port.py "$mc" >/dev/null
    git add -A minecraft
    if git diff --cached --quiet; then
      echo "$branch: up to date"
    else
      git commit -q -m "build: generate minecraft/ for $mc from $(git rev-parse --short "$base")"
      echo "$branch: updated"
    fi
    if [ "$push" = 1 ]; then git push -q -u origin "$branch"; fi
  )
  git worktree remove --force "$wt"
done
