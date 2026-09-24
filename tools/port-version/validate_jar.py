#!/usr/bin/env python3
"""Basic production-jar validation for a built MCVoice loader jar.

    validate_jar.py <jar> --loader fabric --minecraft 26.3 --java 25

Checks: deterministic file name, loader metadata present and fully expanded,
entry point + core classes present, vendored Opus relocated, no class file
newer than the target Java, no sources/secrets bundled. Exit 1 on failure.
"""
import argparse
import json
import re
import sys
import zipfile

ENTRY = {
    "fabric": "dev/mcvoice/platform/fabric/McVoiceFabric.class",
    "legacyfabric": "dev/mcvoice/platform/fabric/McVoiceFabric.class",
    "forge": "dev/mcvoice/platform/forge/McVoiceForge.class",
}
META = {
    "fabric": ["fabric.mod.json"],
    "legacyfabric": ["fabric.mod.json"],
    "forge": ["META-INF/mods.toml", "mcmod.info", "META-INF/neoforge.mods.toml"],
}
REQUIRED = [
    "dev/mcvoice/client/core/VoiceClient.class",
    "dev/mcvoice/client/proximity/PlaybackValidator.class",
    "dev/mcvoice/client/transport/TransportSelector.class",
    "dev/mcvoice/client/svc/SvcCompat.class",
    "dev/mcvoice/thirdparty/concentus/OpusDecoder.class",
    "mcvoice-defaults.properties",
    "assets/mcvoice/lang/en_us.json",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("--loader", required=True)
    ap.add_argument("--minecraft", required=True)
    ap.add_argument("--java", type=int, required=True)
    ap.add_argument("--version", default=None)
    a = ap.parse_args()
    errors = []
    name = a.jar.rsplit("/", 1)[-1]
    pat = rf"^mcvoice-\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?-mc{re.escape(a.minecraft)}-{a.loader}\.jar$"
    if not re.match(pat, name):
        errors.append(f"jar name {name!r} does not match {pat}")
    z = zipfile.ZipFile(a.jar)
    names = set(z.namelist())
    if ENTRY[a.loader] not in names:
        errors.append(f"entry point {ENTRY[a.loader]} missing")
    for r in REQUIRED:
        if r not in names:
            errors.append(f"{r} missing")
    metas = [m for m in META[a.loader] if m in names]
    if not metas:
        errors.append(f"no loader metadata ({META[a.loader]})")
    for m in metas:
        text = z.read(m).decode("utf-8", "replace")
        if "${" in text:
            errors.append(f"{m} contains unexpanded placeholders")
        if m == "fabric.mod.json":
            try:
                j = json.loads(text)
                if j.get("id") != "mcvoice":
                    errors.append("fabric.mod.json id is not mcvoice")
            except ValueError as e:
                errors.append(f"fabric.mod.json invalid: {e}")
    if any(n.startswith("io/github/jaredmdobson/") for n in names):
        errors.append("unrelocated concentus classes bundled")
    for n in names:
        if n.endswith((".java", ".env")) or re.search(r"(^|/)\.env", n):
            errors.append(f"source/secret-like file bundled: {n}")
    max_major = a.java + 44
    too_new = []
    for n in names:
        if n.endswith(".class"):
            head = z.read(n)[:8]
            if head[:4] == b"\xca\xfe\xba\xbe":
                major = int.from_bytes(head[6:8], "big")
                if major > max_major:
                    too_new.append((n, major))
    if too_new:
        errors.append(f"{len(too_new)} classes newer than Java {a.java}, e.g. {too_new[0]}")
    classes = sum(1 for n in names if n.endswith(".class"))
    if classes < 150:
        errors.append(f"only {classes} classes: incomplete jar")
    if errors:
        for e in errors:
            print("INVALID:", e)
        return 1
    print(f"valid: {name} ({classes} classes, metadata {metas})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
