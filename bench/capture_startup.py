"""Cold/warm start samples using ``am start -W`` plus the app's HOME_READY marker.

Ported from the harness used for the reference measurements; the device path
has not been re-executed on a TV after porting.

* cold: ``am force-stop`` (kills only the app's process; app data, login, disk
  image cache and the OS page cache are kept), then ``am start -W``.
* warm: the app must already be in the foreground; the tool presses HOME
  (after a foreground check) and then brings it back with ``am start -W``.

Record the compile state with every result (the tool does). For a "first
install" measurement, reinstall and do NOT run ``cmd package compile`` first;
that state was never measured in the reference data.

Example:
  uv run --no-project python bench/capture_startup.py --serial <adb-serial> --mode cold --count 5 --out results/startup-x.json
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import shlex
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from marquee_bench.device import Device, record_context  # noqa: E402
from marquee_bench.markers import parse_startup  # noqa: E402
from marquee_bench.stats import median  # noqa: E402


def parse_am(text: str) -> dict:
    out = {}
    for key in ("TotalTime", "WaitTime"):
        m = re.search(rf"{key}: (\d+)", text)
        out[key.lower()] = int(m.group(1)) if m else None
    m = re.search(r"LaunchState: (\w+)", text)
    out["launch_state"] = m.group(1) if m else None
    out["ok"] = "Status: ok" in text
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--serial", required=True)
    ap.add_argument("--package", default=os.environ.get("MARQUEE_PACKAGE", "com.mav.marquee"))
    ap.add_argument("--activity", default=".MainActivity")
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--adb-server-port", type=int, default=None)
    ap.add_argument("--mode", choices=["cold", "warm"], required=True)
    ap.add_argument("--count", type=int, default=5)
    ap.add_argument("--marker-timeout", type=float, default=10.0)
    ap.add_argument("--settle", type=float, default=3.0, help="idle seconds between samples")
    ap.add_argument("--out", required=True, type=pathlib.Path)
    a = ap.parse_args(argv)

    dev = Device(a.serial, a.package, adb=a.adb, adb_server_port=a.adb_server_port)
    ctx = record_context(dev)
    component = f"{a.package}/{a.activity}"
    samples = []
    for i in range(a.count):
        since = dev.shell("date '+%m-%d %H:%M:%S.000'").strip()
        if a.mode == "cold":
            dev.shell(f"am force-stop {shlex.quote(a.package)}")
            time.sleep(1.0)
        else:
            dev.send_keys(3)  # HOME, only after a foreground check
            time.sleep(1.5)
        am = parse_am(dev.shell(f"am start -W -n {shlex.quote(component)}", timeout=30))
        ready = None
        deadline = time.monotonic() + a.marker_timeout
        while time.monotonic() < deadline:
            log = dev.adb_cmd("logcat", "-d", "-v", "threadtime", "-T", since, "-s", "Marquee:I", timeout=15)
            procs = parse_startup(log)
            hits = [h for p in procs for h in p["home_ready"] if h["launch"] == a.mode]
            if hits:
                ready = hits[-1]
                stages = procs[-1]["stages"]
                break
            time.sleep(0.5)
        else:
            stages = {}
        samples.append({"sample": i + 1, **am, "home_ready_ms": ready["ms"] if ready else None,
                        "cache": ready["cache"] if ready else None, "stages_ms": stages})
        print(f"{a.mode} {i + 1}: am={am['totaltime']} ms home_ready={samples[-1]['home_ready_ms']} ms")
        time.sleep(a.settle)
    hr = [s["home_ready_ms"] for s in samples if s["home_ready_ms"] is not None]
    result = {"context": ctx, "mode": a.mode, "samples": samples,
              "home_ready_median_ms": median(hr), "n_with_marker": len(hr),
              "notes": "cold = process-cold with warm disk/image cache and saved login unless you cleared them"}
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
