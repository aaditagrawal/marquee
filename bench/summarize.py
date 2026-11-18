"""Recompute every summary number in docs/benchmarks from the raw sample CSVs.

    uv run --no-project python bench/summarize.py            # writes data/summary.json
    uv run --no-project python bench/summarize.py --check    # fails if summary.json is stale

Frame statistics are recomputed from ``frame_samples.csv`` (per-frame totals),
not copied from ``frame_runs.csv``; a mismatch between the two is an error.
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from marquee_bench.stats import median, summarize  # noqa: E402

DATA = pathlib.Path(__file__).resolve().parents[1] / "docs" / "benchmarks" / "data"
SURFACES = ["home", "movies", "series", "search", "osd"]

# Accepted build sets, in chronological order. Every run id listed here has
# status "accepted" in frame_runs.csv. Runs are pooled per surface.
BUILD_SETS = [
    {"key": "r9-baseline", "label": "r9 baseline", "apk": "5b6b9df0", "protocol_note": "r9 reset",
     "runs": {"home": ["r9-baseline-home-exact"], "movies": ["r9-baseline-movies"], "series": ["r9-baseline-series"],
              "search": ["r9-baseline-search"], "osd": ["r9-baseline-osd-final"]}},
    {"key": "r9-qa", "label": "r9 QA build", "apk": "4519f3d3", "protocol_note": "r9 reset",
     "runs": {"home": ["r9-anchor-home"], "movies": ["r9-anchor-movies"], "series": ["r9-anchor-series"],
              "search": ["r9-anchor-search-retry"], "osd": ["r9-anchor-osd-retry"]}},
    {"key": "r9-final", "label": "r9 final", "apk": "f0370893", "protocol_note": "r9 reset",
     "runs": {"home": ["r9-final-home"], "movies": ["r9-final-movies"], "series": ["r9-final-series"],
              "search": ["r9-final-search"], "osd": ["r9-final-osd"]}},
    {"key": "r10-before", "label": "r10 baseline", "apk": "f0370893", "protocol_note": "paced reset (same APK as r9 final)",
     "runs": {"home": ["r10-before-home-paced"], "movies": ["r10-before-movies"], "series": ["r10-before-series"],
              "search": ["r10-before-search"], "osd": ["r10-before-osd"]}},
    {"key": "r10-final", "label": "r10 final", "apk": "3053e78e", "protocol_note": "paced reset",
     "runs": {"home": ["r10-final-home"], "movies": ["r10-final-movies"], "series": ["r10-final-series"],
              "search": ["r10-final-search"], "osd": ["r10-final-osd"]}},
    {"key": "r11-before", "label": "r11 baseline", "apk": "3053e78e", "protocol_note": "paced reset (same APK as r10 final)",
     "runs": {"home": ["r11-before-home"], "movies": ["r11-before-movies"], "series": ["r11-before-series"],
              "search": ["r11-before-search"], "osd": ["r11-before-osd"]}},
    {"key": "r11-accepted", "label": "r11 accepted", "apk": "89ac29d5", "protocol_note": "paced reset",
     "runs": {"home": ["r11-accepted-home"], "movies": ["r11-accepted-movies"], "series": ["r11-accepted-series"],
              "search": ["r11-accepted-search"], "osd": ["r11-accepted-osd"]}},
    {"key": "r11-final2", "label": "r11 final", "apk": "cfd54fa0", "protocol_note": "paced reset",
     "runs": {"home": ["r11-final2-home", "r11-repeat1-home", "r11-repeat2-home", "r11-repeat3-home"],
              "movies": ["r11-fixed-movies", "r11-final2-movies"], "series": ["r11-final2-series"],
              "search": ["r11-final2-search"], "osd": ["r11-final2-osd"]}},
    {"key": "r12-7098", "label": "r12 candidate", "apk": "7098d3c6", "protocol_note": "paced reset",
     "runs": {"home": ["r12-7098-home"], "movies": ["r12-7098-movies"], "series": ["r12-7098-series"],
              "search": ["r12-7098-search"], "osd": []}},
    {"key": "r12-final", "label": "r12 final", "apk": "f266c76a", "protocol_note": "paced reset",
     "runs": {"home": ["r12-clip1-home", "r12-clip2-home", "r12-clip3-home"], "movies": ["r12-clip1-movies", "r12-clip2-movies"],
              "series": ["r12-finalrest-series"], "search": ["r12-finalrest-search"], "osd": []}},
]

# Same-APK controlled comparisons (variant flag only, sequential, minutes apart).
CONTROLLED = [
    {"key": "movies-grid", "surface": "movies", "apk": "eec21927", "a_label": "legacy lazy grid", "a": ["r9-pool-off-movies-hidreset"],
     "b_label": "retained 5-row grid", "b": ["r9-pool-on-movies-hidreset"]},
    {"key": "movies-prefetch", "surface": "movies", "apk": "5b6b9df0", "a_label": "lazy prefetch off", "a": ["r9-movies-no-prefetch"],
     "b_label": "lazy prefetch on", "b": ["r9-baseline-movies"]},
    {"key": "home-parent-anchor", "surface": "home", "apk": "eec21927", "a_label": "parent anchor off", "a": ["r9-parent-off-home"],
     "b_label": "parent anchor on", "b": ["r9-parent-on-home"]},
    {"key": "home-bitmaps", "surface": "home", "apk": "618e6561", "a_label": "software RGB565", "a": ["r4-home-swbitmap"],
     "b_label": "hardware bitmaps", "b": ["r4-home-hwbitmap"]},
    {"key": "movies-bitmaps", "surface": "movies", "apk": "618e6561", "a_label": "software RGB565", "a": ["r4-movies-swbitmap"],
     "b_label": "hardware bitmaps", "b": ["r4-movies-hwbitmap"]},
    {"key": "series-bitmaps", "surface": "series-detail", "apk": "618e6561", "a_label": "software RGB565", "a": ["r4-series-swbitmap"],
     "b_label": "hardware bitmaps", "b": ["r4-series-hwbitmap"]},
]

# Cross-build comparisons: same held-input file and start position, but
# different APKs captured at different times under live load.
OBSERVATIONAL = [
    {"key": "r10", "before": "r10-before", "after": "r10-final", "gap": "~1 h", "note": "Player and UI changes both landed."},
    {"key": "r11", "before": "r11-before", "after": "r11-accepted", "gap": "~2.5 h", "note": "Native Home rail + cached text layouts."},
    {"key": "r11-r12", "before": "r11-final2", "after": "r12-final", "gap": "~1.8 h", "note": "Home native viewport bridge + clipping."},
    {"key": "r10-r12", "before": "r10-final", "after": "r12-final", "gap": "~5.3 h", "note": "Spans r11 and r12; least well matched."},
]


def read_csv(name):
    with open(DATA / name, newline="") as fh:
        return list(csv.DictReader(fh))


def num(x):
    return None if x in ("", None) else float(x)


def build(strict=True):
    runs = read_csv("frame_runs.csv")
    samples = read_csv("frame_samples.csv")
    by_run: dict[tuple[str, str], list[float]] = {}
    for s in samples:
        by_run.setdefault((s["run_id"], s["run"]), []).append(int(s["total_ns"]) / 1e6)
    run_index = {}
    problems = []
    for r in runs:
        key = (r["run_id"], r["run"])
        run_index.setdefault(r["run_id"], []).append(r)
        if int(r["n_frames"] or 0) == 0:
            continue
        recomputed = summarize(by_run.get(key, []))
        for field in ("p50_ms", "p99_ms", "late_pct"):
            stored = num(r[field])
            if stored is None or abs(stored - recomputed[field]) > 0.002:
                problems.append(f"{key} {field}: stored {stored} vs recomputed {recomputed[field]}")
        if recomputed["n"] != int(r["n_frames"]):
            problems.append(f"{key} n mismatch")
    if problems and strict:
        raise SystemExit("frame_runs.csv disagrees with frame_samples.csv:\n" + "\n".join(problems[:20]))

    def pooled(run_ids):
        vals, per_run = [], []
        for rid in run_ids:
            rows = run_index.get(rid)
            if not rows:
                raise SystemExit(f"unknown run id {rid}")
            for r in rows:
                if r["status"] not in ("accepted", "control"):
                    raise SystemExit(f"{rid} has status {r['status']}; only accepted/control runs may be pooled")
                v = by_run.get((rid, r["run"]), [])
                vals += v
                s = summarize(v)
                per_run.append({"run_id": rid, "run": int(r["run"]), "captured_at": r["captured_at"], "n": s["n"],
                                "late_pct": s["late_pct"], "p50_ms": s["p50_ms"], "p99_ms": s["p99_ms"]})
        s = summarize(vals)
        s["runs"] = per_run
        s["apk_sha256"] = sorted({run_index[rid][0]["apk_sha256"] for rid in run_ids})
        return s

    out = {"schema": 1, "statistic": "nearest-rank percentiles of FrameCompleted - IntendedVsync (ms); late = > 16.7 ms",
           "build_sets": [], "controlled": [], "observational": [], "startup": [], "playback_accepted": [],
           "memory_final": [], "apk_sizes": []}
    for bs in BUILD_SETS:
        entry = {k: bs[k] for k in ("key", "label", "apk", "protocol_note")}
        entry["surfaces"] = {s: (pooled(bs["runs"][s]) if bs["runs"].get(s) else None) for s in SURFACES}
        out["build_sets"].append(entry)
    for c in CONTROLLED:
        out["controlled"].append({**{k: c[k] for k in ("key", "surface", "apk", "a_label", "b_label")},
                                  "a": pooled(c["a"]), "b": pooled(c["b"])})
    sets = {e["key"]: e for e in out["build_sets"]}
    for o in OBSERVATIONAL:
        rows = {}
        for s in SURFACES:
            a, b = sets[o["before"]]["surfaces"][s], sets[o["after"]]["surfaces"][s]
            if a and b:
                rows[s] = {"before": {k: a[k] for k in ("n", "late_pct", "p50_ms", "p99_ms")},
                           "after": {k: b[k] for k in ("n", "late_pct", "p50_ms", "p99_ms")}}
        out["observational"].append({**o, "matched": "same protocol/start; different APK, time and live load",
                                     "surfaces": rows})

    # Startup: group by campaign/apk/compile/protocol.
    groups = {}
    for r in read_csv("startup_samples.csv"):
        if r["status"] in ("excluded",):
            continue
        k = (r["campaign"], r["apk_sha256"][:8], r["compile_state"], r["protocol"], r["status"])
        groups.setdefault(k, []).append(r)
    for (camp, apk, comp, proto, status), rows in sorted(groups.items()):
        hr = [float(r["home_ready_ms"]) for r in rows if r["home_ready_ms"]]
        am = [float(r["am_total_ms"]) for r in rows if r["am_total_ms"]]
        out["startup"].append({"campaign": camp, "apk": apk, "compile_state": comp, "protocol": proto, "status": status,
                               "n_home_ready": len(hr), "home_ready_median_ms": median(hr),
                               "home_ready_min_ms": min(hr) if hr else None, "home_ready_max_ms": max(hr) if hr else None,
                               "n_am": len(am), "am_total_median_ms": median(am),
                               "am_total_min_ms": min(am) if am else None, "am_total_max_ms": max(am) if am else None})

    for r in read_csv("playback_samples.csv"):
        if r["status"] in ("accepted", "partial") and r["event"] != "none":
            out["playback_accepted"].append({k: r[k] for k in (
                "case_id", "campaign", "apk_sha256", "media", "event", "play_method", "since_activity_ms",
                "prepare_to_first_frame_ms", "audio_since_activity_ms", "seek_to_frame_ms", "landing_delta_ms",
                "position_policy", "status")})
    for r in read_csv("memory_samples.csv"):
        if r["apk_sha256"].startswith("f266c76a") and r["status"] == "accepted":
            out["memory_final"].append({k: r[k] for k in ("sample_id", "context", "total_pss_kib", "java_heap_kib",
                                                          "native_heap_kib", "code_kib")})
    for r in read_csv("apk_artifacts.csv"):
        out["apk_sizes"].append({k: r[k] for k in ("file", "apk_sha256", "bytes", "build", "built_at")})
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="exit 1 if summary.json differs from a fresh recomputation")
    a = ap.parse_args()
    fresh = json.dumps(build(), indent=1, sort_keys=True) + "\n"
    target = DATA / "summary.json"
    if a.check:
        if not target.exists() or target.read_text() != fresh:
            print("summary.json is stale; run bench/summarize.py", file=sys.stderr)
            return 1
        print("summary.json is up to date")
        return 0
    target.write_text(fresh)
    print(f"wrote {target.relative_to(DATA.parents[2])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
