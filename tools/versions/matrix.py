#!/usr/bin/env python3
"""Validate the version matrix, merge CI build results, and render supported.md.

Subcommands:
  validate                 structural checks on versions/versions.json (+ build-status.json)
  record  MC LOADER STATUS [--jar NAME] [--run URL] [--reason TEXT]
                           record one CI build result in versions/build-status.json
  merge   DIR              merge every *.json result file found in DIR (CI artifacts)
  render                   write versions/supported.md
  targets [--only-built]   print JSON list of {minecraft, loader, flavor, java, build_jdk}

A combination is only "supported" when build-status.json says status == "pass",
which only CI writes after producing and validating the remapped production JAR.
Standard library only.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MATRIX = os.path.join(ROOT, "versions", "versions.json")
STATUS = os.path.join(ROOT, "versions", "build-status.json")
SUPPORTED = os.path.join(ROOT, "versions", "supported.md")

VALID_STATUS = {"pass", "fail", "not-attempted"}
RELEASE_ID = re.compile(r"^\d+\.\d+(\.\d+)?$")


def load(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def save(path, data):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, sort_keys=True)
        fh.write("\n")


def loader_label(entry, loader):
    info = entry["loaders"][loader]
    if loader == "fabric" and info.get("available") and info.get("flavor") == "legacyfabric":
        return "legacyfabric"
    return loader


def cmd_validate(_args) -> int:
    m = load(MATRIX)
    errors = []
    if not m:
        print("versions/versions.json missing", file=sys.stderr)
        return 1
    seen = set()
    prev_time = ""
    for e in m["versions"]:
        mc = e["minecraft"]
        if not RELEASE_ID.match(mc):
            errors.append(f"{mc}: not a stable release id (snapshot/pre/rc ids are forbidden)")
        if mc in seen:
            errors.append(f"{mc}: duplicate")
        seen.add(mc)
        if e["release_time"] < prev_time:
            errors.append(f"{mc}: not sorted by release time")
        prev_time = e["release_time"]
        if e["branch"] != f"mc/{mc}":
            errors.append(f"{mc}: branch must be mc/{mc}")
        if not isinstance(e["java"], int) or e["java"] < 8:
            errors.append(f"{mc}: invalid java {e['java']}")
        for loader in ("forge", "fabric"):
            info = e["loaders"].get(loader)
            if info is None:
                errors.append(f"{mc}: missing loader entry {loader}")
                continue
            if not info.get("available") and not info.get("reason"):
                errors.append(f"{mc}/{loader}: unavailable entries need a reason")
            if loader == "fabric" and info.get("available") and info.get("flavor") not in ("fabric", "legacyfabric"):
                errors.append(f"{mc}/fabric: flavor must be fabric or legacyfabric")
    st = load(STATUS, {"schema": 1, "results": {}})
    for mc, loaders in st.get("results", {}).items():
        if mc not in seen:
            errors.append(f"build-status: {mc} not in matrix")
            continue
        entry = next(e for e in m["versions"] if e["minecraft"] == mc)
        for label, res in loaders.items():
            if res.get("status") not in VALID_STATUS:
                errors.append(f"build-status {mc}/{label}: invalid status {res.get('status')}")
            base = "fabric" if label == "legacyfabric" else label
            if base not in entry["loaders"] or not entry["loaders"][base].get("available"):
                if res.get("status") == "pass":
                    errors.append(f"build-status {mc}/{label}: pass recorded for an unavailable loader")
            elif loader_label(entry, base) != label:
                errors.append(f"build-status {mc}/{label}: label mismatch, expected {loader_label(entry, base)}")
            if res.get("status") == "pass" and not res.get("jar"):
                errors.append(f"build-status {mc}/{label}: pass without jar name")
    for err in errors:
        print("ERROR:", err, file=sys.stderr)
    print(f"validated {len(seen)} versions, {len(errors)} errors")
    return 1 if errors else 0


def cmd_record(args) -> int:
    st = load(STATUS, {"schema": 1, "results": {}})
    res = {
        "status": args.status,
        "checked_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    if args.jar:
        res["jar"] = args.jar
    if args.run:
        res["run"] = args.run
    if args.reason:
        res["reason"] = args.reason[:500]
    if args.commit:
        res["commit"] = args.commit
    st.setdefault("results", {}).setdefault(args.mc, {})[args.loader] = res
    save(STATUS, st)
    return 0


def cmd_merge(args) -> int:
    st = load(STATUS, {"schema": 1, "results": {}})
    n = 0
    for path in sorted(glob.glob(os.path.join(args.dir, "**", "*.json"), recursive=True)):
        data = load(path)
        if not isinstance(data, dict) or "minecraft" not in data or "loader" not in data:
            continue
        rec = {k: v for k, v in data.items() if k not in ("minecraft", "loader")}
        st.setdefault("results", {}).setdefault(data["minecraft"], {})[data["loader"]] = rec
        n += 1
    save(STATUS, st)
    print(f"merged {n} results")
    return 0


def fmt_cell(entry, loader, st):
    info = entry["loaders"][loader]
    if not info.get("available"):
        return "N/A"
    label = loader_label(entry, loader)
    res = st.get("results", {}).get(entry["minecraft"], {}).get(label)
    suffix = " (Legacy Fabric)" if label == "legacyfabric" else ""
    if not res:
        return "not built" + suffix
    return {"pass": "PASS", "fail": "FAIL", "not-attempted": "not built"}[res["status"]] + suffix


def cmd_render(_args) -> int:
    m = load(MATRIX)
    st = load(STATUS, {"schema": 1, "results": {}})
    lines = [
        "# Supported Minecraft versions",
        "",
        "Generated by `tools/versions/matrix.py render` from `versions/versions.json` (official",
        "Mojang/Fabric/Legacy Fabric/Forge metadata) and `versions/build-status.json` (CI results).",
        "",
        "* **PASS** - CI built and validated the production JAR for this combination.",
        "* **FAIL** - CI attempted the build and it failed (see `build-status.json` for the reason).",
        "* **not built** - the loader exists upstream but no CI build has been attempted yet.",
        "  This is *not* support.",
        "* **N/A** - no loader build exists upstream for this Minecraft version.",
        "",
        f"Matrix generated at {m['generated_at']}; latest release in Mojang manifest: `{m['latest_release_in_manifest']}`.",
        "",
        "| Minecraft | Java | Forge | Fabric / Legacy Fabric | Forge version | Fabric loader |",
        "|---|---|---|---|---|---|",
    ]
    for e in m["versions"]:
        f = e["loaders"]["forge"]
        fb = e["loaders"]["fabric"]
        lines.append(
            "| {mc} | {java} | {forge} | {fabric} | {fv} | {fl} |".format(
                mc=e["minecraft"],
                java=e["java"],
                forge=fmt_cell(e, "forge", st),
                fabric=fmt_cell(e, "fabric", st),
                fv=f.get("version", "-") if f.get("available") else "-",
                fl=fb.get("loader", "-") if fb.get("available") else "-",
            )
        )
    passed = sum(1 for r in st.get("results", {}).values() for x in r.values() if x.get("status") == "pass")
    lines += ["", f"**{passed}** Minecraft/loader combinations currently pass CI.", ""]
    unavailable = [
        (e["minecraft"], l, e["loaders"][l]["reason"])
        for e in m["versions"]
        for l in ("forge", "fabric")
        if not e["loaders"][l].get("available")
    ]
    if unavailable:
        lines += ["## Unavailable combinations", "", "| Minecraft | Loader | Reason |", "|---|---|---|"]
        lines += [f"| {mc} | {l} | {r} |" for mc, l, r in unavailable]
        lines.append("")
    with open(SUPPORTED, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"wrote {SUPPORTED}")
    return 0


def cmd_targets(args) -> int:
    m = load(MATRIX)
    st = load(STATUS, {"schema": 1, "results": {}})
    out = []
    for e in m["versions"]:
        if args.minecraft and e["minecraft"] not in args.minecraft.split(","):
            continue
        for loader in ("forge", "fabric"):
            info = e["loaders"][loader]
            if not info.get("available"):
                continue
            label = loader_label(e, loader)
            res = st.get("results", {}).get(e["minecraft"], {}).get(label)
            if args.only_built and not (res and res.get("status") == "pass"):
                continue
            out.append({
                "minecraft": e["minecraft"],
                "loader": label,
                "java": e["java"],
                "build_jdk": e["build_jdk"],
                "branch": e["branch"],
            })
    json.dump(out, sys.stdout)
    sys.stdout.write("\n")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("validate")
    r = sub.add_parser("record")
    r.add_argument("mc")
    r.add_argument("loader", choices=["forge", "fabric", "legacyfabric"])
    r.add_argument("status", choices=sorted(VALID_STATUS))
    r.add_argument("--jar")
    r.add_argument("--run")
    r.add_argument("--reason")
    r.add_argument("--commit")
    mg = sub.add_parser("merge")
    mg.add_argument("dir")
    sub.add_parser("render")
    t = sub.add_parser("targets")
    t.add_argument("--only-built", action="store_true")
    t.add_argument("--minecraft")
    args = ap.parse_args()
    return {
        "validate": cmd_validate,
        "record": cmd_record,
        "merge": cmd_merge,
        "render": cmd_render,
        "targets": cmd_targets,
    }[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
