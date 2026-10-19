#!/usr/bin/env python3
"""Publish bounded diagnostic DATA in a separate job that never executes test artifacts."""
import argparse
from pathlib import Path
import re
import subprocess
import time


def stage(source, destination):
    destination.mkdir(parents=True, exist_ok=True)
    for file in source.iterdir():
        if file.is_symlink() or not file.is_file() or not re.fullmatch(r"[A-Za-z0-9_.-]+\.(json|log|txt)", file.name):
            raise ValueError("unexpected diagnostic artifact entry")
        with file.open("rb") as stream:
            stream.seek(max(0, file.stat().st_size - 400000))
            data = stream.read(400000)
        (destination / file.name).write_bytes(data)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("source", type=Path)
    p.add_argument("category", choices=["builds", "smoke", "svc-interop"])
    p.add_argument("minecraft")
    a = p.parse_args()
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", a.minecraft):
        raise ValueError("invalid Minecraft version")
    def git(*args, **kwargs):
        return subprocess.run(["git", *args], check=True, **kwargs)
    git("fetch", "origin", "tooling/probe-output")
    git("worktree", "add", "--detach", "diagnostic-output", "FETCH_HEAD")
    checkout = Path("diagnostic-output")
    stage(a.source, checkout / a.category / a.minecraft)
    git("-C", str(checkout), "config", "user.name", "github-actions[bot]")
    git("-C", str(checkout), "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("-C", str(checkout), "add", "--", a.category)
    if subprocess.run(["git", "-C", str(checkout), "diff", "--cached", "--quiet"]).returncode == 0:
        return
    git("-C", str(checkout), "commit", "-m", f"diagnostics {a.category} {a.minecraft}")
    for attempt in range(5):
        if subprocess.run(["git", "-C", str(checkout), "push", "origin", "HEAD:tooling/probe-output"]).returncode == 0:
            return
        time.sleep(attempt + 1)
        git("-C", str(checkout), "fetch", "origin", "tooling/probe-output")
        git("-C", str(checkout), "rebase", "FETCH_HEAD")
    raise RuntimeError("diagnostic push failed after retries")


if __name__ == "__main__":
    main()
