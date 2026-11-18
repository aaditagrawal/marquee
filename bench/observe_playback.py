"""Passively record Marquee player timing markers while you start/seek playback.

Read-only: sends no input and changes no settings. Start this, then start,
resume or seek playback on the TV with the remote. It reads ``logcat`` for the
MarqueePlayer markers documented in marquee_bench/markers.py and writes one
JSON record per player session. Item IDs, URLs and titles are never stored.

Ported from the harness used for the reference measurements; the device path
has not been re-executed on a TV after porting.

Example:
  uv run --no-project python bench/observe_playback.py --serial <adb-serial> --seconds 60 --label 4k-hevc-direct --out results/play.json
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from marquee_bench.device import Device, record_context  # noqa: E402
from marquee_bench.markers import parse_playback  # noqa: E402


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--serial", required=True)
    ap.add_argument("--package", default=os.environ.get("MARQUEE_PACKAGE", "com.mav.marquee"))
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--adb-server-port", type=int, default=None)
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--label", required=True, help="your neutral description of the media (codec/resolution/HDR/audio/container)")
    ap.add_argument("--out", required=True, type=pathlib.Path)
    a = ap.parse_args(argv)
    dev = Device(a.serial, a.package, adb=a.adb, adb_server_port=a.adb_server_port)
    ctx = record_context(dev)
    since = dev.shell("date '+%m-%d %H:%M:%S.000'").strip()
    print(f"recording for {a.seconds:.0f} s; start or seek playback now")
    time.sleep(a.seconds)
    log = dev.adb_cmd("logcat", "-d", "-v", "threadtime", "-T", since, "-s", "MarqueePlayer:I", timeout=30)
    sessions = parse_playback(log)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps({"context": ctx, "media_label": a.label, "sessions": sessions}, indent=2))
    for s in sessions:
        print(f"session {s['index']}: {s['method']}/{s['mode']} prepare->frame={s['prepare_to_first_frame_ms']} ms "
              f"seeks={s['seeks_to_frame_ms']} failure={s['failure']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
