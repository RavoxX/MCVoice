#!/usr/bin/env python3
"""Aggregate saved client/presence/loopback runs without discarding saturated results."""
import argparse
import json
from pathlib import Path
import re
import hashlib
from statistics import median

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("directory", type=Path)
p.add_argument("--out", type=Path, required=True)
p.add_argument("--candidate", default="01d1e52")
args = p.parse_args()
result = {"client_runs": {}, "presence_runs": {}, "relay_runs": {}}
for phase in ("before", "after"):
    client = []
    for file in sorted(args.directory.glob(f"client-{phase}-*.jsonl")):
        client.append([json.loads(line) for line in file.read_text(encoding="utf-8-sig").splitlines() if line.startswith("{")])
    result["client_runs"][phase] = client
    presence = []
    for line in (args.directory / f"go-presence-{phase}.txt").read_text(encoding="utf-8-sig").splitlines():
        match = re.match(r"BenchmarkPresenceStable/(\d+)-\d+\s+\d+\s+(\d+) ns/op\s+(\d+) B/op\s+(\d+) allocs/op", line)
        if match:
            n, ns, size, allocations = map(int, match.groups())
            presence.append(dict(sessions=n, ns_per_op=ns, bytes_per_op=size, allocations_per_op=allocations))
    result["presence_runs"][phase] = presence
    relay = []
    for file in sorted((args.directory / phase).glob("*.json")):
        if file.name.endswith(".cpu.json"): continue
        run = json.loads(file.read_text())
        run["backend_measurement"] = json.loads(file.with_suffix(".cpu.json").read_text())
        # Store portable evidence, not paths specific to the operator's machine.
        run["backend_measurement"].pop("command", None)
        run["run"] = file.stem
        relay.append(run)
    result["relay_runs"][phase] = relay
result["environment"] = {
    "os": "Windows amd64", "cpu": "AMD Ryzen 7 5800X3D", "java": "JDK 17",
    "go": "1.24.2", "rust": "1.88.0", "baseline": "c8dc4061ead14845af9fc736e99b3cde59cc7daf",
    "candidate": args.candidate, "client_heap": "512 MiB", "workers": 4,
    "repeats": 3, "clients": 200, "talker_ratio": 0.3, "duration": "10s", "ramp": "5s",
    "peer_refresh": "1s", "churn": False,
    "limitations": "Loopback, generator and backend share host. Before and after are separate measurement phases; implementation order alternates within each phase. Client JVMs run sequentially. No 100k capacity claim."
}
result["client_source_sha256"] = [{"source": str(file).replace('\\', '/'), "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
    for file in sorted(Path('client').glob('**/src/main/java/**/*.java'))]
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
for case in [r["case"] for r in result["client_runs"]["before"][0]]:
    print(case, {phase: {field: median(r[field] for run in result["client_runs"][phase] for r in run if r["case"] == case)
        for field in ("ns_per_op", "bytes_per_op")} for phase in ("before", "after")})
for n in (100, 1000):
    print("presence", n, {phase: {field: median(r[field] for r in result["presence_runs"][phase] if r["sessions"] == n)
        for field in ("ns_per_op", "bytes_per_op", "allocations_per_op")} for phase in ("before", "after")})
for impl in ("go", "rust"):
    for group in (10, 50):
        print("relay", impl, group, {phase: {field: median(r[field] for r in result["relay_runs"][phase]
            if r["run"].startswith(impl + "-") and r["run"].split("-")[-2] == str(group))
            for field in ("delivery_ratio", "latency_ms_p95", "sent_pps", "relayed_pps")} for phase in ("before", "after")})
