"""Held-D-pad frame-time capture for one surface of an Android TV app.

Ported from the harness used for the reference measurements in
docs/benchmarks. The device paths of this port have NOT been re-executed on a
TV after porting; the parsing/statistics code is unit-tested offline.

Protocol (see bench/README.md):
  1. Install the build under test, compile it (``cmd package compile -m speed -f <pkg>``),
     and put the app on the surface you want to measure with focus at the start position.
  2. Run this tool. It verifies the package is in the foreground before every
     input, drives one warm-up pass (default), then N measured passes, polling
     ``dumpsys gfxinfo <pkg> framestats`` during each pass.
  3. It writes context.json (device identity, APK SHA-256, compile state),
     runN-frames.json, runN-summary.json, meminfo.txt and complete.json.

Example:
  uv run --no-project python bench/capture_frames.py --serial <adb-serial> \\
      --surface home --reference-path home --runs 3 --out results/home-build-x
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from marquee_bench import framestats, held_input  # noqa: E402
from marquee_bench.device import Device, ForegroundError, record_context  # noqa: E402

REMOTE = "/data/local/tmp/marquee-bench-held.json"


def capture_pass(dev: Device, remote: str, poll_s: float, max_s: float) -> tuple[str, list[dict], dict]:
    dev.reset_framestats()
    lo = dev.uptime_ns()
    proc = dev.run_hid(remote)
    dumps, started = [], time.monotonic()
    try:
        while proc.poll() is None:
            if time.monotonic() - started > max_s:
                raise RuntimeError(f"held input exceeded {max_s} s")
            dumps.append(dev.framestats())
            time.sleep(poll_s)
    finally:
        if proc.poll() is None:
            proc.terminate()
    if proc.returncode != 0:
        raise RuntimeError("hid exited with an error")
    hi = dev.uptime_ns()
    dumps.append(dev.framestats())
    raw = "\n".join(dumps)
    rows = framestats.parse(raw, lo, hi)
    summary = {"window_ns": [lo, hi], "drive_seconds": round(time.monotonic() - started, 3),
               **framestats.summarize_rows(rows)}
    return raw, rows, summary


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Held-D-pad frame-time capture (HWUI framestats).")
    ap.add_argument("--serial", required=True, help="adb serial of the TV (required; no default device)")
    ap.add_argument("--package", default=os.environ.get("MARQUEE_PACKAGE", "com.mav.marquee"))
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--adb-server-port", type=int, default=None, help="optional non-default adb server port")
    ap.add_argument("--surface", required=True, help="label stored with the result (e.g. home, movies)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--reference-path", choices=sorted(held_input.REFERENCE_PATHS))
    g.add_argument("--path", help='custom legs, e.g. "right:1700,down:760,left:1700"')
    ap.add_argument("--reset-path", help="legs replayed before each measured pass to return to the start position")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--no-warmup", action="store_true", help="skip the unmeasured warm-up pass")
    ap.add_argument("--poll", type=float, default=0.65, help="seconds between framestats polls")
    ap.add_argument("--expect-sha256", help="refuse to run unless the installed APK has this SHA-256")
    ap.add_argument("--require-compile", default=None, help="refuse unless compile status contains this (e.g. speed)")
    ap.add_argument("--min-frames", type=int, default=50)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    a = ap.parse_args(argv)

    legs = held_input.REFERENCE_PATHS[a.reference_path] if a.reference_path else held_input.parse_path(a.path)
    reset_legs = held_input.parse_path(a.reset_path) if a.reset_path else None
    dev = Device(a.serial, a.package, adb=a.adb, adb_server_port=a.adb_server_port)
    a.out.mkdir(parents=True, exist_ok=False)
    ctx = record_context(dev)
    if a.expect_sha256 and ctx["apk_sha256"] != a.expect_sha256:
        print("installed APK does not match --expect-sha256; refusing", file=sys.stderr)
        return 2
    if a.require_compile and not any(a.require_compile in s for s in ctx["compile_status"]):
        print(f"compile status {ctx['compile_status']} lacks {a.require_compile!r}; refusing", file=sys.stderr)
        return 2
    if ctx["debuggable"]:
        print("warning: debuggable build; results are not representative of release performance", file=sys.stderr)
    ctx.update(surface=a.surface, path=legs, reset_path=reset_legs, runs=a.runs, warmup=not a.no_warmup,
               poll_s=a.poll, pid=dev.pid(), statistic="nearest-rank; late = FrameCompleted-IntendedVsync > 16.7 ms")
    (a.out / "context.json").write_text(json.dumps(ctx, indent=2))
    max_s = held_input.duration_ms(legs) / 1000 + 15
    try:
        dev.push_text(held_input.generate(legs), REMOTE)
        if reset_legs:
            dev.push_text(held_input.generate(reset_legs), REMOTE.replace(".json", "-reset.json"))
        if not a.no_warmup:
            dev.require_foreground()
            dev.run_hid(REMOTE).wait(timeout=max_s)
        for n in range(1, a.runs + 1):
            if reset_legs:
                dev.run_hid(REMOTE.replace(".json", "-reset.json")).wait(timeout=held_input.duration_ms(reset_legs) / 1000 + 15)
            raw, rows, summary = capture_pass(dev, REMOTE, a.poll, max_s)
            (a.out / f"run{n}-gfxinfo.txt").write_text(raw)
            (a.out / f"run{n}-frames.json").write_text(json.dumps(rows))
            (a.out / f"run{n}-summary.json").write_text(json.dumps(summary, indent=2))
            t = summary["total"]
            print(f"run {n}: n={t['n']} late={t['late_pct']}% p50={t['p50_ms']} p99={t['p99_ms']} ms")
            if t["n"] < a.min_frames:
                raise RuntimeError(f"only {t['n']} frames; is the app actually animating on this path?")
        if dev.pid() != ctx["pid"]:
            raise RuntimeError("app process restarted during capture; exclude this result")
        (a.out / "meminfo.txt").write_text(dev.meminfo())
        (a.out / "complete.json").write_text(json.dumps({"valid": True}))
    except ForegroundError as e:
        print(f"stopped: {e}", file=sys.stderr)
        return 3
    except (RuntimeError, subprocess.SubprocessError) as e:
        print(f"capture failed, result incomplete: {e}", file=sys.stderr)
        return 1
    finally:
        try:
            dev.remove_tmp(REMOTE)
            dev.remove_tmp(REMOTE.replace(".json", "-reset.json"))
        except subprocess.SubprocessError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
