#!/usr/bin/env python3
"""Render release-report.md from the artifacts of a release.yml run.

    report.py <artifacts-dir>   (environment: VERSION, TEST_RESULT, BUILD_RESULT,
                                 IMAGES_RESULT, IMAGES_REQUESTED, LATEST, RUN_URL, GITHUB_REPOSITORY)

Only facts found in the artifacts are reported: a jar, release or package that
is not recorded as successful is listed as failed or missing, with the reason.
"""
import json
import os
from pathlib import Path
import re
import sys


def vkey(v):
    return tuple(int(p) for p in re.findall(r"\d+", v))


def require_complete(root, builds, releases):
    expected = set(json.loads(os.environ["EXPECTED_MINECRAFT"]))
    errors = []
    if not expected or set(builds) != expected or set(releases) != expected:
        errors.append("build/release versions do not match the release plan")
    manifests = {}
    for file in Path(root).rglob("expected-loaders.txt"):
        rows = [json.loads(p.read_text(encoding="utf-8")) for p in file.parent.glob("*.json")]
        rows = [r for r in rows if "loader" in r and "minecraft" in r]
        versions = {r["minecraft"] for r in rows}
        loaders = file.read_text(encoding="utf-8").split()
        if len(versions) != 1 or not loaders or len(set(loaders)) != len(loaders):
            errors.append(f"invalid loader manifest: {file.name}")
            continue
        mc = next(iter(versions))
        if mc in manifests or {r["loader"] for r in rows} != set(loaders) or len(rows) != len(loaders):
            errors.append(f"incomplete/duplicate loader records: {mc}")
        manifests[mc] = set(loaders)
    if set(manifests) != expected:
        errors.append("missing expected-loader manifests")
    for mc, loaders in builds.items():
        if set(loaders) != manifests.get(mc, set()):
            errors.append(f"unexpected loader records: {mc}")
        for loader, row in loaders.items():
            maven = row.get("maven", "")
            if row["status"] != "pass" or not row.get("jar"):
                errors.append(f"build not complete: {mc}/{loader}")
            if not (maven == "published" or maven.startswith("exists:")) or not re.fullmatch(r"[0-9a-f]{64}", row.get("maven_sha256", "")):
                errors.append(f"Maven read-back not verified: {mc}/{loader}")
    for mc, row in releases.items():
        if row["release"] not in ("created", "updated"):
            errors.append(f"GitHub release not complete: {mc}: {row['release']}")
    for gate in ("TEST_RESULT", "BUILD_RESULT"):
        if os.environ.get(gate) != "success":
            errors.append(f"{gate} did not pass")
    if os.environ.get("IMAGES_REQUESTED", "true") == "true" and os.environ.get("IMAGES_RESULT") != "success":
        errors.append("requested backend images did not pass")
    if errors:
        raise ValueError("Incomplete release: " + "; ".join(errors))


def main():
    root = sys.argv[1]
    env = os.environ.get
    version = env("VERSION", "?")
    repo = env("GITHUB_REPOSITORY", "RavoxX/MCVoice")
    owner_repo = repo.lower()
    builds = {}
    releases = {}
    # download-artifact flattens a single matching artifact into the destination,
    # even with merge-multiple=false. Multiple matches retain named directories.
    for f in sorted(Path(root).rglob("*.json")):
        r = json.loads(f.read_text(encoding="utf-8"))
        if "minecraft" in r and "loader" in r and "status" in r:
            builds.setdefault(r["minecraft"], {})[r["loader"]] = r
        elif "minecraft" in r and "tag" in r and "release" in r:
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
    n_old = sum(1 for v in builds.values() for l in v.values() if l.get("maven", "").startswith("exists"))
    old = f" ({n_old} more already in GitHub Packages from an earlier run, not replaced)" if n_old else ""
    w(f"Jars built and validated: **{n_pass}/{n_all}**. GitHub Releases: **{n_rel}/{len(versions)}**. "
      f"Maven packages published: **{n_mvn}/{n_all}**{old}.\n")

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
            coord = f"io.github.ravoxx.mcvoice:{artifact}:{version}+mc{mc}"
            if mvn == "published":
                mvn = coord
            elif mvn.startswith("exists"):
                mvn = f"{coord} (already published earlier, not replaced)"
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
    if "--require-complete" in sys.argv[2:]:
        require_complete(root, builds, releases)


if __name__ == "__main__":
    main()
