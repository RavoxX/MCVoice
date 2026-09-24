#!/usr/bin/env python3
"""Render release-report.md from the artifacts of a release.yml run.

    report.py <artifacts-dir>   (environment: VERSION, TEST_RESULT, BUILD_RESULT,
                                 IMAGES_RESULT, IMAGES_REQUESTED, LATEST, RUN_URL, GITHUB_REPOSITORY)

Only facts found in the artifacts are reported: a jar, release or package that
is not recorded as successful is listed as failed or missing, with the reason.
"""
import glob
import json
import os
import re
import sys


def vkey(v):
    return tuple(int(p) for p in re.findall(r"\d+", v))


def main():
    root = sys.argv[1]
    env = os.environ.get
    version = env("VERSION", "?")
    repo = env("GITHUB_REPOSITORY", "RavoxX/MCVoice")
    owner_repo = repo.lower()
    builds = {}
    for f in glob.glob(os.path.join(root, "mc-*-status", "*.json")):
        r = json.load(open(f))
        builds.setdefault(r["minecraft"], {})[r["loader"]] = r
    releases = {}
    for f in glob.glob(os.path.join(root, "release-result-*", "*.json")):
        r = json.load(open(f))
        releases[r["minecraft"]] = r
    versions = sorted(set(builds) | set(releases), key=vkey)

    out = []
    w = out.append
    w(f"# MCVoice {version} release report\n")
    w(f"Workflow run: {env('RUN_URL', '-')}\n")
    w("## Summary\n")
    w("| Stage | Result |\n|---|---|")
    w(f"| Protocol vectors, Go + Rust backends, conformance, client core | {env('TEST_RESULT', '?')} |")
    w(f"| Minecraft builds | {env('BUILD_RESULT', '?')} |")
    img = env("IMAGES_RESULT", "?") if env("IMAGES_REQUESTED", "true") == "true" else "not requested"
    w(f"| Backend images (GHCR) | {img} |")
    w("")

    n_pass = sum(1 for v in builds.values() for l in v.values() if l["status"] == "pass")
    n_all = sum(len(v) for v in builds.values())
    n_rel = sum(1 for r in releases.values() if r["release"] in ("created", "updated"))
    n_mvn = sum(1 for v in builds.values() for l in v.values() if l.get("maven") == "published")
    w(f"Jars built and validated: **{n_pass}/{n_all}**. GitHub Releases: **{n_rel}/{len(versions)}**. "
      f"Maven packages published: **{n_mvn}/{n_all}**.\n")

    w("## Minecraft versions\n")
    w("| Minecraft | Loader | Build | Jar | GitHub Release | Maven package |\n|---|---|---|---|---|---|")
    for mc in versions:
        rel = releases.get(mc, {"release": "missing", "detail": "no release job result", "tag": f"v{version}-mc{mc}"})
        rel_cell = rel["release"] if rel["release"] in ("created", "updated") else f"{rel['release']}: {rel.get('detail', '')[:160]}"
        if rel["release"] in ("created", "updated"):
            rel_cell = f"[{rel['tag']}](https://github.com/{repo}/releases/tag/{rel['tag']})"
        for loader, b in sorted(builds.get(mc, {}).items()):
            build = "pass" if b["status"] == "pass" else f"FAIL ({b.get('reason', '')[:160]})"
            artifact = {"fabric": "voice-client-fabric", "forge": "voice-client-forge",
                        "legacyfabric": "voice-client-legacyfabric"}.get(loader, loader)
            mvn = b.get("maven", "not attempted")
            if mvn == "published":
                mvn = f"io.github.ravoxx.mcvoice:{artifact}:{version}+mc{mc}"
            w(f"| {mc} | {loader} | {build} | {b.get('jar', '-')} | {rel_cell} | {mvn} |")
        if mc not in builds:
            w(f"| {mc} | - | no build result | - | {rel_cell} | - |")
    w("")

    w("## Backend images\n")
    if img == "success":
        tags = [version] + (["latest"] if env("LATEST") == "true" else [])
        for impl in ("rust", "go"):
            w(f"* `ghcr.io/{owner_repo}/voice-backend-{impl}`: " + ", ".join(f"`{t}`" for t in tags))
    else:
        w(f"Images were not published (result: {img}). See the `images` job of the run.")
    w("")
    w("## Not released\n")
    w("Versions without a release above, and every Minecraft version outside this release, are listed "
      "with their reasons in `versions/supported.md` (generated from CI build results).")
    print("\n".join(out))


if __name__ == "__main__":
    main()
