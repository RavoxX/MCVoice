#!/usr/bin/env python3
"""Check Minecraft log records without treating loader TRACE diagnostics as failures."""
import os
from pathlib import Path
import re
import sys


HEADER = re.compile(r"^\[[^\]]+\] \[[^\]]*/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\]")
FRAME = re.compile(r"^\s+at (?:[^/\s]+/)*dev\.mcvoice\.", re.MULTILINE)


def check_log(text, minecraft, loader):
    initialised = re.search(r"MCVoice .* initialised \(Minecraft " + re.escape(minecraft)
                            + r", " + re.escape(loader) + r"[,)]", text)
    if not initialised:
        return "MCVoice did not log its initialisation"
    if "HUD render failed" in text:
        return "HUD render failed"

    records = []
    level, lines = None, []
    for line in text.splitlines():
        header = HEADER.match(line)
        if header:
            records.append((level, "\n".join(lines)))
            level, lines = header[1], []
        lines.append(line)
    records.append((level, "\n".join(lines)))
    for level, record in records:
        # Forge logs caught ClassNotFoundExceptions while checking optional mods at TRACE.
        if level in ("TRACE", "DEBUG"):
            continue
        if FRAME.search(record):
            return "stack trace through MCVoice: " + record.splitlines()[0][:250]
        if level in ("ERROR", "FATAL") and ("MCVoice" in record or "dev.mcvoice." in record):
            return "MCVoice error: " + record.splitlines()[0][:250]
    return ""


def main():
    path, minecraft, loader = sys.argv[1:]
    log = Path(path)
    failure = check_log(log.read_text(errors="replace"), minecraft, loader) if log.is_file() else "no game log (" + path + ")"
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("fail=" + failure + "\n")
    print(failure or "MCVoice initialised; no MCVoice runtime error found")
    return bool(failure)


if __name__ == "__main__":
    sys.exit(main())
