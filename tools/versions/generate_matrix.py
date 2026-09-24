#!/usr/bin/env python3
"""Generate versions/versions.json from official Minecraft and loader metadata.

Sources (all public, read-only):
  * Mojang launcher manifest   https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
  * Per-version Mojang JSON    (javaVersion.majorVersion)
  * Fabric meta                https://meta.fabricmc.net/v2/...
  * Fabric maven (fabric-api)  https://maven.fabricmc.net/...
  * Legacy Fabric meta         https://meta.legacyfabric.net/v2/...
  * Legacy Fabric maven        https://maven.legacyfabric.net/...
  * Forge maven + promotions   https://maven.minecraftforge.net/... , files.minecraftforge.net

Only stable releases (manifest type == "release") are included. Snapshots,
pre-releases, release candidates, April Fools and experimental builds are never
listed because Mojang does not publish them with type "release".

Availability ("available") only states that a loader build exists upstream.
Whether *we* support a combination is tracked separately in
versions/build-status.json, which only CI writes (see tools/versions/status.py).

Usage: generate_matrix.py [--min 1.8] [--max 26.3] [--out versions/versions.json]
Only the Python standard library is used so this runs on any CI image.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_META = "https://meta.fabricmc.net/v2"
FABRIC_API_MAVEN = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
LEGACY_META = "https://meta.legacyfabric.net/v2"
LEGACY_API_MAVEN = "https://maven.legacyfabric.net/net/legacyfabric/legacy-fabric-api/legacy-fabric-api/maven-metadata.xml"
FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"

UA = "MCVoice-version-matrix/1 (+https://github.com/RavoxX/MCVoice)"


def fetch(url: str, retries: int = 4) -> bytes:
    delay = 2.0
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read()
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt == retries - 1:
                raise RuntimeError(f"failed to fetch {url}: {exc}") from exc
            time.sleep(delay)
            delay *= 2
    raise AssertionError("unreachable")


def fetch_json(url: str):
    return json.loads(fetch(url))


def maven_versions(url: str) -> list[str]:
    root = ET.fromstring(fetch(url))
    return [v.text for v in root.iter("version") if v.text]


def version_key(v: str) -> tuple:
    """Numeric sort key for release ids like 1.8, 1.21.11, 26.1.2."""
    return tuple(int(p) for p in re.findall(r"\d+", v))


def pick_latest(candidates: list[str]) -> str | None:
    if not candidates:
        return None
    return max(candidates, key=version_key)


def build_jdk_for(runtime_java: int, mc: str) -> int:
    """JDK used to *run Gradle* for this version (the compile target stays runtime_java).

    Old toolchains (Forge 1.8-1.16, Legacy Fabric) compile with a Java 8 toolchain
    but modern Gradle plugins need a newer JDK to execute, so the build JDK is the
    maximum of the runtime Java and 17 (21 for 1.20.5+ era plugins, 25 for 26.x).
    """
    if runtime_java >= 25:
        return runtime_java
    if runtime_java >= 21:
        return 21
    return 17 if runtime_java < 17 else runtime_java


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min", default="1.8")
    ap.add_argument("--max", default="26.3")
    ap.add_argument("--out", default="versions/versions.json")
    args = ap.parse_args()

    manifest = fetch_json(MOJANG_MANIFEST)
    releases = [v for v in manifest["versions"] if v["type"] == "release"]
    by_id = {v["id"]: v for v in releases}
    if args.min not in by_id:
        print(f"minimum version {args.min} is not an official release", file=sys.stderr)
        return 2
    lo = by_id[args.min]["releaseTime"]
    max_present = args.max in by_id
    hi = by_id[args.max]["releaseTime"] if max_present else "9999"
    selected = sorted(
        (v for v in releases if lo <= v["releaseTime"] <= hi),
        key=lambda v: v["releaseTime"],
    )

    def detail(v):
        d = fetch_json(v["url"])
        jv = d.get("javaVersion") or {}
        return v["id"], {
            "java": int(jv.get("majorVersion", 8)),
            "java_component": jv.get("component", "jre-legacy"),
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        details = dict(pool.map(detail, selected))

    # Fabric (official)
    fabric_games = {g["version"]: g for g in fetch_json(f"{FABRIC_META}/versions/game")}
    fabric_intermediary = {g["version"] for g in fetch_json(f"{FABRIC_META}/versions/intermediary")}
    fabric_loader = next(l["version"] for l in fetch_json(f"{FABRIC_META}/versions/loader") if l.get("stable"))
    fabric_api = maven_versions(FABRIC_API_MAVEN)

    # Legacy Fabric
    try:
        legacy_games = {g["version"]: g for g in fetch_json(f"{LEGACY_META}/versions/game")}
        legacy_loader = next(l["version"] for l in fetch_json(f"{LEGACY_META}/versions/loader") if l.get("stable", True))
        legacy_api = maven_versions(LEGACY_API_MAVEN)
        legacy_error = None
    except RuntimeError as exc:  # keep generating, but record why
        legacy_games, legacy_loader, legacy_api, legacy_error = {}, None, [], str(exc)

    # Forge
    forge_all = maven_versions(FORGE_MAVEN)
    forge_by_mc: dict[str, list[str]] = {}
    for fv in forge_all:
        mc, _, rest = fv.partition("-")
        if rest:
            forge_by_mc.setdefault(mc, []).append(fv)
    promos = fetch_json(FORGE_PROMOS).get("promos", {})

    def api_for(api_versions: list[str], mc: str) -> str | None:
        suffix = "+" + mc
        exact = pick_latest([a for a in api_versions if a.endswith(suffix)])
        if exact:
            return exact
        # Older Fabric API builds name only the minor line ("0.42.0+1.16", "0.28.5+1.15"); use the
        # newest build of that line for its latest patch release only (earlier patch releases of a line
        # cannot be assumed compatible with the newest build).
        parts = mc.split(".")
        if len(parts) >= 2:
            line = ".".join(parts[:2])
            same_line = [x["id"] for x in releases if x["id"] == line or x["id"].startswith(line + ".")]
            if same_line and mc == max(same_line, key=version_key):
                return pick_latest([a for a in api_versions if a.endswith("+" + line)])
        return None

    entries = []
    for v in selected:
        mc = v["id"]
        java = details[mc]["java"]
        entry = {
            "minecraft": mc,
            "release_time": v["releaseTime"],
            "java": java,
            "java_component": details[mc]["java_component"],
            "build_jdk": build_jdk_for(java, mc),
            "branch": f"mc/{mc}",
            "loaders": {},
        }

        # --- Forge ---
        forge_versions = forge_by_mc.get(mc, [])
        rec = promos.get(f"{mc}-recommended")
        latest = promos.get(f"{mc}-latest")
        if forge_versions:
            chosen_short = rec or latest
            full = None
            if chosen_short:
                full = next((f for f in forge_versions if f.split("-")[1] == chosen_short), None)
            full = full or max(forge_versions, key=lambda f: version_key(f.split("-", 1)[1]))
            entry["loaders"]["forge"] = {
                "available": True,
                "version": full,
                "channel": "recommended" if rec else "latest",
            }
        else:
            entry["loaders"]["forge"] = {
                "available": False,
                "reason": f"No Forge build for Minecraft {mc} is published on maven.minecraftforge.net",
            }

        # --- Fabric / Legacy Fabric ---
        official = mc in fabric_games and mc in fabric_intermediary
        if official:
            api = api_for(fabric_api, mc)
            entry["loaders"]["fabric"] = {
                "available": True,
                "flavor": "fabric",
                "loader": fabric_loader,
                "fabric_api": api,
                "note": None if api else "Fabric API has no build for this version; the mod must not depend on it",
            }
        elif mc in legacy_games:
            api = api_for(legacy_api, mc)
            entry["loaders"]["fabric"] = {
                "available": True,
                "flavor": "legacyfabric",
                "loader": legacy_loader,
                "legacy_fabric_api": api,
                "note": "Legacy Fabric (community project, not official FabricMC)",
            }
        else:
            reason = "No compatible official Fabric or Legacy Fabric loader exists for this version"
            if legacy_error:
                reason += f" (Legacy Fabric metadata unavailable during generation: {legacy_error})"
            entry["loaders"]["fabric"] = {"available": False, "reason": reason}
        entries.append(entry)

    out = {
        "schema": 1,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "range": {"min": args.min, "max": args.max, "max_is_released": max_present},
        "sources": {
            "mojang": MOJANG_MANIFEST,
            "fabric": FABRIC_META,
            "legacy_fabric": LEGACY_META,
            "forge": FORGE_MAVEN,
        },
        "latest_release_in_manifest": manifest["latest"]["release"],
        "versions": entries,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)
        fh.write("\n")
    print(f"wrote {len(entries)} releases ({entries[0]['minecraft']} .. {entries[-1]['minecraft']}) to {args.out}")
    if not max_present:
        print(f"note: --max {args.max} is not a released version; matrix ends at {entries[-1]['minecraft']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
