#!/usr/bin/env python3
"""Generate the per-version Minecraft build (minecraft/) for one release.

    port.py 26.3                     # write ./minecraft for 26.3
    port.py 1.20.1 --out /tmp/b      # somewhere else
    port.py --list                   # show family/loader plan for every release

Inputs: versions/versions.json (official metadata), client/platform/families.json
(which adapter sources + build generation serve which versions). Output is a
self-contained Gradle build that compiles the in-tree client core and the
family's platform sources for every loader that both exists upstream and is
implemented by the family. Nothing is copied: sources are referenced from the
repository, so an mc/<version> branch is just main + this generated directory.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import stat
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from preprocess import process  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))


def vkey(v: str):
    return tuple(int(p) for p in re.findall(r"\d+", v))


def load(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as fh:
        return json.load(fh)


def family_for(mc: str, families):
    for f in families["families"]:
        if in_range(mc, f["minecraft"]):
            return f
    return None


def in_range(mc: str, r) -> bool:
    return vkey(r["min"]) <= vkey(mc) <= vkey(r["max"])


def loader_config(fam, label, mc):
    """The family's build setup for this loader and Minecraft version (a dict, or a list of ranged dicts)."""
    cfg = fam["loaders"].get(label)
    if isinstance(cfg, list):
        cfg = next((c for c in cfg if in_range(mc, c["minecraft"])), None)
    return cfg


def plan(mc: str):
    """Return (entry, family, [(loader_label, loader_cfg, upstream_info)], reasons)."""
    matrix = load("versions/versions.json")
    families = load("client/platform/families.json")
    entry = next((e for e in matrix["versions"] if e["minecraft"] == mc), None)
    if entry is None:
        raise SystemExit(f"{mc} is not a stable release in versions/versions.json")
    fam = family_for(mc, families)
    targets, reasons = [], {}
    for loader in ("fabric", "forge"):
        up = entry["loaders"][loader]
        label = "legacyfabric" if loader == "fabric" and up.get("flavor") == "legacyfabric" else loader
        if not up.get("available"):
            reasons[label] = up.get("reason", "unavailable upstream")
            continue
        cfg = loader_config(fam, label, mc) if fam else None
        if cfg is None:
            reasons[label] = ("not implemented yet for this Minecraft version (no adapter family)" if fam is None else
                              f"not implemented yet: adapter family '{fam['id']}' has no {label} build setup for this version")
            continue
        targets.append((label, cfg, up))
    return entry, fam, targets, reasons


def render(path, tokens):
    with open(path, encoding="utf-8") as fh:
        s = fh.read()
    for k, v in tokens.items():
        s = s.replace("@" + k + "@", str(v))
    left = re.findall(r"@[A-Z_]+@", s)
    if left:
        raise SystemExit(f"unreplaced tokens in {path}: {sorted(set(left))}")
    return s


def write(path, text, mode=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    if mode:
        os.chmod(path, mode)


def generate(mc: str, out: str):
    """Write one standalone Gradle build per loader (out/<loader>/), so loaders can use different
    build-system generations (e.g. ForgeGradle 6 on Gradle 8 next to Loom on Gradle 9)."""
    entry, fam, targets, reasons = plan(mc)
    if not targets:
        raise SystemExit(f"nothing buildable for {mc}: {reasons}")
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    java = entry["java"]
    loaders = {}
    for label, cfg, up in targets:
        buildgen = cfg["buildgen"]
        tdir = os.path.join(HERE, "templates", buildgen)
        ldir = os.path.join(out, label)
        props = {
            "minecraft_version": mc,
            "mod_version": load_version(),
            "maven_group": "io.github.ravoxx.mcvoice",
            "java_version": java,
            "org.gradle.jvmargs": "-Xmx3g",
            "org.gradle.caching": "true",
        }
        if label in ("fabric", "legacyfabric"):
            props["fabric_loader"] = up["loader"]
            api = up.get("fabric_api") or up.get("legacy_fabric_api")
            if api:
                props["fabric_api"] = api
        if label == "forge":
            props["forge_version"] = up["version"]
        variants = [src for v in fam.get("variants", [])
                    if in_range(mc, v["minecraft"]) for src in v["sources"]]
        loader_src = cfg.get("sources", "fabric" if label == "legacyfabric" else label)
        # "parts" replaces the shared part too, for families whose loaders use different mapping sets
        parts = cfg.get("parts") or (["common"] + variants + [loader_src])
        generate_sources(fam["id"], parts, mc, label, os.path.join(ldir, "src-gen"))
        tokens = {"MC": mc, "JAVA": java, "FAMILY": fam["id"], "PLUGIN_VERSION": cfg["plugin_version"], "LOADER": label,
                  "MAPPINGS": cfg.get("mappings", "")}
        props.update(cfg.get("gradle_properties", {}))
        write(os.path.join(ldir, "build.gradle"), render(os.path.join(tdir, f"{label}.gradle"), tokens))
        settings = os.path.join(tdir, "settings.gradle")
        if not os.path.exists(settings):
            settings = os.path.join(HERE, "templates", "settings.gradle")
        write(os.path.join(ldir, "settings.gradle"), render(settings, tokens))
        write(os.path.join(ldir, "gradle.properties"),
              "# GENERATED by tools/port-version/port.py - do not edit by hand.\n"
              + "".join(f"{k}={v}\n" for k, v in props.items()))
        write_wrapper(ldir, cfg["gradle"])
        loaders[label] = {
            "buildgen": buildgen,
            "gradle": cfg["gradle"],
            "gradle_jdk": max(entry["build_jdk"], cfg.get("gradle_jdk", 0)),
            "plugin_version": cfg["plugin_version"],
        }
    meta = {
        "minecraft": mc,
        "family": fam["id"],
        "java": java,
        "build_jdk": entry["build_jdk"],
        "jdks": sorted({java, entry["build_jdk"]} | {l["gradle_jdk"] for l in loaders.values()}),
        "loaders": list(loaders),
        "builds": loaders,
        "not_built": reasons,
    }
    write(os.path.join(out, "build-info.json"), json.dumps(meta, indent=2) + "\n")
    write(os.path.join(out, "README.md"),
          f"# MCVoice for Minecraft {mc}\n\nGenerated by `tools/port-version/port.py {mc}` from `main`.\n"
          f"Adapter family `{fam['id']}`, Java {java}. Each loader is a standalone Gradle build:\n\n"
          + "".join(f"* `{k}/`: build generation `{v['buildgen']}`, Gradle {v['gradle']} on JDK {v['gradle_jdk']}"
                    f" (`cd minecraft/{k} && ./gradlew build`; jar in `minecraft/{k}/build/libs/`)\n"
                    for k, v in loaders.items())
          + "".join(f"* {k}: not built - {v}\n" for k, v in reasons.items()))
    print(json.dumps({k: meta[k] for k in ("minecraft", "family", "java", "jdks", "loaders")}))
    return meta


def write_wrapper(d, gradle_version):
    write(os.path.join(d, "gradle", "wrapper", "gradle-wrapper.properties"),
          "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
          f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{gradle_version}-bin.zip\n"
          "networkTimeout=10000\nvalidateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n")
    shutil.copy(os.path.join(HERE, "wrapper", "gradle-wrapper.jar"), os.path.join(d, "gradle", "wrapper", "gradle-wrapper.jar"))
    for f in ("gradlew", "gradlew.bat"):
        shutil.copy(os.path.join(HERE, "wrapper", f), os.path.join(d, f))
    os.chmod(os.path.join(d, "gradlew"), os.stat(os.path.join(d, "gradlew")).st_mode | stat.S_IEXEC)


TEXT_EXT = (".java", ".json", ".toml", ".properties", ".mcmeta", ".info", ".cfg", ".txt", ".md")


def generate_sources(family, parts, mc, loader, dest):
    """Preprocess the family's source parts for (mc, loader) into dest/{java,resources}."""
    base = os.path.join(ROOT, "client", "platform", family)
    for part in parts:
        for kind in ("java", "resources"):
            src = os.path.join(base, part, "src", "main", kind)
            if not os.path.isdir(src):
                continue
            for d, _, files in os.walk(src):
                for f in files:
                    sp = os.path.join(d, f)
                    rel = os.path.relpath(sp, src)
                    dp = os.path.join(dest, kind, rel)
                    if os.path.exists(dp):
                        raise SystemExit(f"{rel} is provided by more than one source part of {family}")
                    os.makedirs(os.path.dirname(dp), exist_ok=True)
                    if f.endswith(TEXT_EXT):
                        with open(sp, encoding="utf-8") as fh:
                            text = process(fh.read(), mc, loader, os.path.relpath(sp, ROOT))
                        with open(dp, "w", encoding="utf-8") as fh:
                            fh.write(text)
                    else:
                        shutil.copyfile(sp, dp)


def load_version():
    with open(os.path.join(ROOT, "client", "gradle.properties"), encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("mod_version="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("mod_version missing")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("minecraft", nargs="?")
    ap.add_argument("--out", default=os.path.join(ROOT, "minecraft"))
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--json", action="store_true", help="with --list: machine readable")
    args = ap.parse_args()
    if args.list:
        matrix = load("versions/versions.json")
        rows = []
        for e in matrix["versions"]:
            _, fam, targets, reasons = plan(e["minecraft"])
            rows.append({"minecraft": e["minecraft"], "family": fam["id"] if fam else None,
                         "loaders": [t[0] for t in targets], "build_jdk": e["build_jdk"], "reasons": reasons})
        if args.json:
            print(json.dumps(rows))
        else:
            for r in rows:
                print(f"{r['minecraft']:>8}  {str(r['family']):8}  {','.join(r['loaders']) or '-':22}  {r['reasons']}")
        return 0
    if not args.minecraft:
        ap.error("minecraft version required")
    generate(args.minecraft, os.path.abspath(args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
