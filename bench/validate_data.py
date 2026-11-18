"""Validate docs/benchmarks/data against its schema (see data/SCHEMA.md).

    uv run --no-project python bench/validate_data.py [--data DIR]

Checks column sets, types, enums, units, cross-file references, that
summary.json is a fresh recomputation, and a privacy screen (no private IPv4
addresses, local paths, credentials or 32-hex server IDs). Exit 0 = valid.
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
DEFAULT = HERE.parent / "docs" / "benchmarks" / "data"

SHA = re.compile(r"^([0-9a-f]{64}|unattributed)$")
TS = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d$")
RUN_STATUS = {"accepted", "control", "superseded", "historical", "excluded", "incomplete", "failed", "misrouted",
              "invalid", "diagnostic", "proof-only"}
PLAY_STATUS = {"accepted", "baseline", "partial", "superseded", "auxiliary", "failed", "misrouted", "excluded", "not-captured"}
START_STATUS = {"accepted", "historical", "control", "incidental", "excluded", "first-launch-after-install"}
SURFACES = {"home", "movies", "series", "search", "osd", "episode-detail", "movie-detail", "series-detail"}


def t_int(v):
    int(v)


def t_float(v):
    float(v)


def t_bool(v):
    if v not in ("True", "False", "true", "false"):
        raise ValueError(v)


def enum(values):
    def check(v):
        if v not in values:
            raise ValueError(f"{v!r} not in {sorted(values)}")
    return check


def regex(rx):
    def check(v):
        if not rx.match(v):
            raise ValueError(v)
    return check


# column -> (validator, required?)
SCHEMAS = {
    "frame_runs.csv": {
        "run_id": (str, True), "run": (t_int, True), "campaign": (str, True), "captured_at": (regex(TS), False),
        "apk_sha256": (regex(SHA), True), "build": (enum({"release", "profile", "release (unattributed)"}), True),
        "aot": (enum({"speed"}), True), "surface": (enum(SURFACES), True), "protocol": (str, True),
        "mode": (enum({"held", "trace", "proof"}), True), "n_frames": (t_int, True),
        **{c: (t_float, False) for c in ("p50_ms", "p90_ms", "p95_ms", "p99_ms", "max_ms", "late_pct",
                                         "main_until_sync_p50_ms", "main_until_sync_p99_ms", "renderthread_p50_ms",
                                         "renderthread_p99_ms", "gpu_ready_p50_ms", "gpu_ready_p99_ms")},
        "late_count": (t_int, False), "launch_total_ms": (t_int, False), "pss_total_kib": (t_int, False),
        "status": (enum(RUN_STATUS), True), "note": (str, False), "source": (str, True)},
    "frame_samples.csv": {
        "run_id": (str, True), "run": (t_int, True), "frame": (t_int, True), "t_ms": (t_float, True),
        "total_ns": (t_int, True), "main_until_sync_us": (t_int, False), "renderthread_us": (t_int, False),
        "gpu_ready_us": (t_int, False), "dequeue_us": (t_int, False), "queue_us": (t_int, False)},
    "startup_samples.csv": {
        "campaign": (str, True), "captured_at": (regex(TS), False), "apk_sha256": (regex(SHA), True),
        "build": (str, True), "compile_state": (enum({"verify", "speed-profile", "speed"}), True),
        "protocol": (enum({"dedicated-cold", "dedicated-warm", "harness-cold"}), True), "sample": (t_int, True),
        "launch_state": (enum({"COLD", "HOT", "WARM"}), False), "am_total_ms": (t_int, False), "am_wait_ms": (t_int, False),
        "home_ready_ms": (t_int, False), **{c: (t_int, False) for c in (
            "stage_application_ms", "stage_session_ready_ms", "stage_snapshot_read_ms", "stage_home_composed_ms",
            "stage_disk_art_ready_ms")},
        "cache": (str, True), "status": (enum(START_STATUS), True), "note": (str, False)},
    "playback_samples.csv": {
        "case_id": (str, True), "campaign": (str, True), "captured_at": (regex(TS), False), "apk_sha256": (regex(SHA), True),
        "build": (str, True), "media": (regex(re.compile(r"^media-[A-Z0-9?]+$")), False), "session": (t_int, False),
        "event": (enum({"start", "resume", "restart", "seek-restart", "seek-in-place", "none"}), True),
        "play_method": (enum({"DirectPlay", "Transcode"}), False), "mode": (str, False), "video_copy": (t_bool, False),
        "server_reasons": (str, False), **{c: (t_int, False) for c in (
            "since_activity_ms", "prepare_to_first_frame_ms", "audio_since_activity_ms", "seek_to_frame_ms", "landing_delta_ms")},
        "requested_s": (t_float, False), "position_policy": (enum({"sync", "exact"}), False),
        "status": (enum(PLAY_STATUS), True), "note": (str, False)},
    "memory_samples.csv": {
        "sample_id": (str, True), "campaign": (str, True), "captured_at": (regex(TS), False), "apk_sha256": (regex(SHA), True),
        "context": (str, True), "large_heap": (enum({"true", "false"}), True),
        **{c: (t_int, False) for c in ("total_pss_kib", "java_heap_kib", "native_heap_kib", "code_kib", "stack_kib",
                                       "graphics_kib", "private_other_kib", "system_kib")},
        "status": (str, True)},
    "apk_artifacts.csv": {
        "file": (regex(re.compile(r"^marquee-[\w.-]+\.apk$")), True), "apk_sha256": (regex(SHA), True), "bytes": (t_int, True),
        "build": (enum({"release", "profile", "debug"}), True), "version_code": (t_int, True), "version_name": (str, True),
        "debuggable": (t_bool, True), "built_at": (str, False)},
    "media_aliases.csv": {"media": (regex(re.compile(r"^media-[A-Z0-9]+$")), True), "descriptor": (str, True)},
}

PRIVACY = [
    (re.compile(r"\b192\.168\.\d{1,3}\.\d{1,3}\b"), "private IPv4 192.168/16"),
    (re.compile(r"\b10\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"), "private IPv4 10/8"),
    (re.compile(r"\b172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}\b"), "private IPv4 172.16/12"),
    (re.compile(r"\b100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b"), "CGNAT/Tailscale range"),
    (re.compile(r"/home/|/Users/|C:\\\\Users"), "local home path"),
    (re.compile(r"api_key|X-Emby-Token|MediaBrowser Token|Authorization:", re.I), "credential marker"),
    (re.compile(r"(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])"), "32-hex server/item ID"),
    (re.compile(r"https?://(?!github\.com|developer\.android\.com|jellyfin\.org)[^\s\"')]+"), "URL"),
]


def validate(data: pathlib.Path) -> list[str]:
    errors = []
    tables = {}
    for name, schema in SCHEMAS.items():
        path = data / name
        if not path.exists():
            errors.append(f"{name}: missing")
            continue
        with open(path, newline="") as fh:
            reader = csv.DictReader(fh)
            cols = reader.fieldnames or []
            missing, extra = set(schema) - set(cols), set(cols) - set(schema)
            if missing or extra:
                errors.append(f"{name}: columns missing {sorted(missing)} extra {sorted(extra)}")
            rows = list(reader)
        tables[name] = rows
        bad = 0
        for i, row in enumerate(rows, start=2):
            for col, (check, required) in schema.items():
                v = row.get(col, "")
                if v == "":
                    if required:
                        errors.append(f"{name}:{i}: {col} is required")
                        bad += 1
                    continue
                try:
                    check(v)
                except (ValueError, TypeError) as e:
                    errors.append(f"{name}:{i}: {col}: {e}")
                    bad += 1
            if bad > 20:
                errors.append(f"{name}: too many errors, stopping")
                break
    if "frame_runs.csv" in tables and "frame_samples.csv" in tables:
        keys = {(r["run_id"], r["run"]) for r in tables["frame_runs.csv"]}
        counts = {}
        for s in tables["frame_samples.csv"]:
            counts[(s["run_id"], s["run"])] = counts.get((s["run_id"], s["run"]), 0) + 1
        for k, n in counts.items():
            if k not in keys:
                errors.append(f"frame_samples.csv: {k} not in frame_runs.csv")
        for r in tables["frame_runs.csv"]:
            if counts.get((r["run_id"], r["run"]), 0) != int(r["n_frames"]):
                errors.append(f"frame_runs.csv: {r['run_id']} run {r['run']} n_frames != sample rows")
    if "playback_samples.csv" in tables and "media_aliases.csv" in tables:
        aliases = {r["media"] for r in tables["media_aliases.csv"]}
        for r in tables["playback_samples.csv"]:
            if r["media"] and r["media"] not in aliases:
                errors.append(f"playback_samples.csv: unknown media alias {r['media']}")
    for f in sorted(data.iterdir()):
        if f.is_file():
            text = f.read_text(errors="replace")
            for rx, what in PRIVACY:
                m = rx.search(text)
                if m:
                    errors.append(f"privacy: {f.name}: {what}: {m.group(0)[:40]!r}")
    for j in ("summary.json", "host_proxies.json", "renderer_spike.json"):
        try:
            json.loads((data / j).read_text())
        except (OSError, ValueError) as e:
            errors.append(f"{j}: {e}")
    if data.resolve() == DEFAULT.resolve():
        import summarize
        fresh = json.dumps(summarize.build(), indent=1, sort_keys=True) + "\n"
        if (data / "summary.json").read_text() != fresh:
            errors.append("summary.json is stale; run bench/summarize.py")
    return errors


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data", type=pathlib.Path, default=DEFAULT)
    a = ap.parse_args(argv)
    errors = validate(a.data)
    for e in errors:
        print(e, file=sys.stderr)
    print("data valid" if not errors else f"{len(errors)} problem(s)")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
