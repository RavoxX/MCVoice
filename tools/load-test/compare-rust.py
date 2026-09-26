#!/usr/bin/env python3
"""Compare saved Rust binaries on loopback; never load-test a production URL."""
import argparse
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import time
import urllib.request


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--backend", action="append", required=True, help="label=/absolute/path/to/binary")
    p.add_argument("--loadtest", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--cases", default="500:10,500:50,1000:10")
    p.add_argument("--duration", default="30s")
    p.add_argument("--repeats", type=int, default=3)
    p.add_argument("--workers", type=int, default=4)
    args = p.parse_args()
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    resource.setrlimit(resource.RLIMIT_NOFILE, (min(8192, hard) if hard != resource.RLIM_INFINITY else 8192, hard))
    args.out.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, AUTH_MODE="offline", CONTROL_BIND_ADDRESS="127.0.0.1", CONTROL_PORT="18880",
               VOICE_BIND_ADDRESS="127.0.0.1", VOICE_UDP_PORT="24456", PUBLIC_VOICE_PORT="24456",
               PUBLIC_HOSTNAME="127.0.0.1", RATE_LIMIT_CONNECT_PER_MIN="0", LOG_LEVEL="warn",
               UDP_WORKERS=str(args.workers), TOKIO_WORKER_THREADS=str(args.workers))
    for case in args.cases.split(","):
        clients, group = case.split(":")
        for repeat in range(args.repeats):
            # Alternate order to reduce systematic warmup/thermal bias.
            backends = args.backend if repeat % 2 == 0 else args.backend[::-1]
            for backend in backends:
                label, binary = backend.split("=", 1)
                stem = args.out / f"{label}-{clients}-{group}-{repeat + 1}"
                with stem.with_suffix(".server.log").open("w") as log:
                    started = time.monotonic()
                    srv = subprocess.Popen([binary], env=env, stdout=log, stderr=log)
                    try:
                        for _ in range(100):
                            if srv.poll() is not None:
                                raise RuntimeError(f"backend exited: {stem}")
                            try:
                                urllib.request.urlopen("http://127.0.0.1:18880/ready", timeout=1).close()
                                break
                            except OSError:
                                time.sleep(0.1)
                        else:
                            raise RuntimeError("backend not ready")
                        command = [str(args.loadtest.resolve()), "-url", "ws://127.0.0.1:18880/v1/control",
                                   "-clients", clients, "-group", group, "-talkers", "0.3", "-duration", args.duration,
                                   "-ramp", "5s", "-seed", str(100 + repeat), "-churn", "0", "-dim-change", "0",
                                   "-server-switch", "0", "-peer-refresh", "1s", "-json", str(stem.with_suffix(".json"))]
                        with stem.with_suffix(".client.log").open("w") as output:
                            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, check=True)
                        with urllib.request.urlopen("http://127.0.0.1:18880/metrics", timeout=3) as response:
                            stem.with_suffix(".metrics").write_bytes(response.read())
                    finally:
                        srv.send_signal(signal.SIGTERM)
                        _, status, usage = os.wait4(srv.pid, 0)
                        srv.returncode = os.waitstatus_to_exitcode(status)
                        wall = time.monotonic() - started
                        cpu = usage.ru_utime + usage.ru_stime
                        stem.with_suffix(".cpu.json").write_text(json.dumps({
                            "backend_cpu_seconds": cpu, "backend_wall_seconds": wall,
                            "backend_cpu_percent": 100 * cpu / wall,
                            "max_rss_native_units": usage.ru_maxrss,
                            "note": "CPU includes ramp, measurement, drain and shutdown; 100% = one core",
                            "command": command, "workers": args.workers,
                        }, indent=2) + "\n")
                result = json.loads(stem.with_suffix(".json").read_text())
                print(stem.name, json.dumps({k: result[k] for k in (
                    "delivery_ratio", "latency_ms_p50", "latency_ms_p95", "latency_ms_p99", "sent_pps", "relayed_pps")}), flush=True)


if __name__ == "__main__":
    main()
