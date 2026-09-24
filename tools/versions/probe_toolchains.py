#!/usr/bin/env python3
"""Print the latest versions of the Gradle plugins / tooling used by the per-version builds.

Used by the version-matrix workflow so maintainers (and the porting tool) can see which
Loom / ForgeGradle / Unimined releases exist without hunting through several mavens.
Writes versions/toolchains.json. Standard library only.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UA = "MCVoice-toolchain-probe/1"

MAVENS = {
    "fabric-loom": "https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml",
    "fabric-loom-legacy-id": "https://maven.fabricmc.net/fabric-loom/fabric-loom.gradle.plugin/maven-metadata.xml",
    "fabric-loom-remap": "https://maven.fabricmc.net/net/fabricmc/fabric-loom-remap/net.fabricmc.fabric-loom-remap.gradle.plugin/maven-metadata.xml",
    "forgegradle": "https://maven.minecraftforge.net/net/minecraftforge/gradle/ForgeGradle/maven-metadata.xml",
    "unimined": "https://maven.wagyourtail.xyz/releases/xyz/wagyourtail/unimined/xyz.wagyourtail.unimined.gradle.plugin/maven-metadata.xml",
    "legacy-looming": "https://maven.legacyfabric.net/legacy-looming/legacy-looming.gradle.plugin/maven-metadata.xml",
    "architectury-loom": "https://maven.architectury.dev/dev/architectury/loom/dev.architectury.loom.gradle.plugin/maven-metadata.xml",
    "neoforge-moddev": "https://maven.neoforged.net/releases/net/neoforged/moddev/net.neoforged.moddev.gradle.plugin/maven-metadata.xml",
}


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def main() -> int:
    out = {}
    for name, url in MAVENS.items():
        try:
            root = ET.fromstring(fetch(url))
            versions = [v.text for v in root.iter("version")]
            out[name] = {"latest": (root.findtext(".//release") or (versions[-1] if versions else None)),
                         "recent": versions[-25:]}
        except Exception as exc:  # noqa: BLE001 - diagnostics only
            out[name] = {"error": str(exc)}
    try:
        svc = json.loads(fetch("https://api.modrinth.com/v2/project/simple-voice-chat/version"))
        out["simple-voice-chat"] = [
            {"version": v["version_number"], "loaders": v["loaders"], "game_versions": v["game_versions"][-3:],
             "published": v["date_published"]}
            for v in svc[:40]
        ]
    except Exception as exc:  # noqa: BLE001
        out["simple-voice-chat"] = {"error": str(exc)}
    path = os.path.join(ROOT, "versions", "toolchains.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)
        fh.write("\n")
    json.dump(out, sys.stdout, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
