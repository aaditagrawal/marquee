"""Regenerate every chart in docs/benchmarks/charts from docs/benchmarks/data.

    uv run --no-project python bench/summarize.py     # refresh data/summary.json first
    uv run --no-project python bench/make_charts.py   # writes charts/*.svg

Pure standard library; output is byte-for-byte deterministic for the same data.
Each SVG carries <title>/<desc>, an explicit background, and light/dark colours
selected with ``prefers-color-scheme`` inside the SVG (GitHub honours this for
images). Palette: three categorical slots validated for colour-vision
deficiency in both themes; identity is never colour-only (direct labels and
distinct marker shapes).
"""
from __future__ import annotations

import csv
import json
import math
import pathlib
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parents[1] / "docs" / "benchmarks"
DATA, CHARTS = ROOT / "data", ROOT / "charts"

STYLE = """
<style>
  .bg{fill:#fcfcfb} .ink{fill:#0b0b0b} .ink2{fill:#52514e} .grid{stroke:#e2e1dc} .axis{stroke:#9a9890}
  .ref{stroke:#6b6a64} .s1{fill:#2a78d6;stroke:#2a78d6} .s2{fill:#eb6834;stroke:#eb6834} .s3{fill:#1baf7a;stroke:#1baf7a}
  .l1{stroke:#2a78d6;fill:none} .l2{stroke:#eb6834;fill:none} .l3{stroke:#1baf7a;fill:none} .link{stroke:#b9b7ae}
  .ring{stroke:#fcfcfb}
  text{font-family:system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
  @media (prefers-color-scheme: dark){
    .bg{fill:#1a1a19} .ink{fill:#f0efec} .ink2{fill:#c3c2b7} .grid{stroke:#33322f} .axis{stroke:#6f6e68}
    .ref{stroke:#a3a29a} .s1{fill:#3987e5;stroke:#3987e5} .s2{fill:#d95926;stroke:#d95926} .s3{fill:#199e70;stroke:#199e70}
    .l1{stroke:#3987e5;fill:none} .l2{stroke:#d95926;fill:none} .l3{stroke:#199e70;fill:none} .link{stroke:#55544f} .ring{stroke:#1a1a19}
  }
</style>
"""


def f(x: float) -> str:
    return f"{x:.1f}".rstrip("0").rstrip(".") if abs(x - round(x)) > 1e-9 else str(int(round(x)))


class Svg:
    def __init__(self, w: int, h: int, title: str, desc: str):
        self.w, self.h = w, h
        self.parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
                      f'role="img" aria-labelledby="t d">',
                      f'<title id="t">{escape(title)}</title><desc id="d">{escape(desc)}</desc>', STYLE,
                      f'<rect class="bg" x="0" y="0" width="{w}" height="{h}" rx="6"/>']

    def add(self, s: str):
        self.parts.append(s)

    def text(self, x, y, s, cls="ink", size=13, anchor="start", weight="normal", rotate=None):
        tr = f' transform="rotate({rotate} {f(x)} {f(y)})"' if rotate is not None else ""
        self.add(f'<text class="{cls}" x="{f(x)}" y="{f(y)}" font-size="{size}" text-anchor="{anchor}" '
                 f'font-weight="{weight}"{tr}>{escape(s)}</text>')

    def line(self, x1, y1, x2, y2, cls="grid", width=1, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.add(f'<line class="{cls}" x1="{f(x1)}" y1="{f(y1)}" x2="{f(x2)}" y2="{f(y2)}" stroke-width="{width}"{d}/>')

    def marker(self, x, y, series: int, shape="circle", r=4.5):
        cls = f"s{series}"
        if shape == "circle":
            self.add(f'<circle class="{cls}" cx="{f(x)}" cy="{f(y)}" r="{r}" stroke-width="0"/>')
        elif shape == "diamond":
            s = r * 1.25
            self.add(f'<path class="{cls}" stroke-width="0" d="M{f(x)} {f(y - s)}L{f(x + s)} {f(y)}L{f(x)} {f(y + s)}L{f(x - s)} {f(y)}Z"/>')
        elif shape == "square":
            s = r * 0.9
            self.add(f'<rect class="{cls}" stroke-width="0" x="{f(x - s)}" y="{f(y - s)}" width="{f(2 * s)}" height="{f(2 * s)}" rx="1"/>')

    def polyline(self, pts, series: int, width=2):
        d = " ".join(f"{f(x)},{f(y)}" for x, y in pts)
        self.add(f'<polyline class="l{series}" fill="none" points="{d}" stroke-width="{width}" stroke-linejoin="round" stroke-linecap="round"/>')

    def save(self, name: str):
        self.add("</svg>")
        (CHARTS / name).write_text("\n".join(self.parts) + "\n")


SHAPES = {1: "circle", 2: "diamond", 3: "square"}


def nice_ticks(lo, hi, n=5):
    span = hi - lo
    raw = span / n
    mag = 10 ** math.floor(math.log10(raw))
    step = min((s * mag for s in (1, 2, 2.5, 5, 10) if s * mag >= raw), default=mag * 10)
    t = math.ceil(lo / step) * step
    out = []
    while t <= hi + 1e-9:
        out.append(round(t, 6))
        t += step
    return out


def load_summary():
    return json.loads((DATA / "summary.json").read_text())


# --------------------------------------------------------------------------
def chart_by_build(summary, metric, name, title, ylabel, ymax, ref_value, ref_label):
    sets = summary["build_sets"]
    series = [("home", "Home rows", 1), ("movies", "Movies grid", 2), ("series", "Episodes rail", 3)]
    W, H = 920, 430
    L, R, T, B = 84, 150, 56, 92
    pw, ph = W - L - R, H - T - B
    xs = [L + pw * (i + 0.5) / len(sets) for i in range(len(sets))]
    y = lambda v: T + ph * (1 - v / ymax)  # noqa: E731
    desc = "; ".join(f"{lab}: " + ", ".join(
        f"{s['label']} {s['surfaces'][k][metric]:.1f}" for s in sets if s["surfaces"][k]) for k, lab, _ in series)
    svg = Svg(W, H, title, desc)
    svg.text(L, 26, title, size=16, weight="600")
    svg.text(L, 44, "Accepted held-D-pad captures per build, oldest to newest. Pooled when a build has several runs.", cls="ink2", size=12)
    for t in nice_ticks(0, ymax):
        svg.line(L, y(t), L + pw, y(t))
        svg.text(L - 8, y(t) + 4, f(t), cls="ink2", size=12, anchor="end")
    svg.text(24, T + ph / 2, ylabel, cls="ink2", size=12, anchor="middle", rotate=-90)
    svg.line(L, T + ph, L + pw, T + ph, cls="axis")
    # protocol break between r9 final and r10 baseline (Home reset changed)
    bi = next(i for i, s in enumerate(sets) if s["key"] == "r10-before")
    bx = (xs[bi - 1] + xs[bi]) / 2
    svg.line(bx, T - 4, bx, T + ph, cls="ref", dash="2 3")
    svg.text(bx + 4, T + 8, "Home reset path changed (r9 vs r10+ Home not matched)", cls="ink2", size=11)
    svg.line(L, y(ref_value), L + pw, y(ref_value), cls="ref", width=1.2, dash="6 4")
    svg.text(L + 6, y(ref_value) - 5, ref_label, cls="ink2", size=11)
    for i, s in enumerate(sets):
        svg.text(xs[i], T + ph + 18, s["label"], cls="ink", size=11.5, anchor="middle")
        svg.text(xs[i], T + ph + 33, s["apk"], cls="ink2", size=10.5, anchor="middle")
        hn = s["surfaces"]["home"]["n"] if s["surfaces"]["home"] else 0
        svg.text(xs[i], T + ph + 47, f"n={hn}", cls="ink2", size=10, anchor="middle")
    svg.text(L, H - 14, "n under each build = Home frames. HWUI FrameCompleted minus IntendedVsync, not display latency. Sony BRAVIA VU1, release + speed AOT.",
             cls="ink2", size=11)
    ends = []
    for k, lab, si in series:
        pts = [(xs[i], y(min(s["surfaces"][k][metric], ymax))) for i, s in enumerate(sets) if s["surfaces"][k]]
        svg.polyline(pts, si)
        for (px, py) in pts:
            svg.marker(px, py, si, SHAPES[si])
        last = [s for s in sets if s["surfaces"][k]][-1]
        ends.append([pts[-1][1], lab, last["surfaces"][k][metric], si])
    ends.sort()
    for j in range(1, len(ends)):  # de-collide end labels
        if ends[j][0] - ends[j - 1][0] < 17:
            ends[j][0] = ends[j - 1][0] + 17
    for yy, lab, v, si in ends:
        svg.marker(L + pw + 10, yy - 4, si, SHAPES[si], r=4)
        svg.text(L + pw + 18, yy, f"{lab} {v:.1f}", size=11.5)
    svg.save(name)


def chart_ecdf(name):
    samples = {}
    with open(DATA / "frame_samples.csv", newline="") as fh:
        for r in csv.DictReader(fh):
            samples.setdefault(r["run_id"], []).append(int(r["total_ns"]) / 1e6)
    summary = load_summary()
    sets = {s["key"]: s for s in summary["build_sets"]}
    panels = [("home", "Home rows"), ("movies", "Movies grid")]
    pairs = [("r10-final", 1, "r10 final (3053e78e)"), ("r12-final", 2, "r12 final (f266c76a)")]
    W, H = 920, 400
    L, T, B, gap = 64, 70, 70, 60
    pw = (W - L - 30 - gap) / 2
    ph = H - T - B
    xmax = 60.0
    desc = []
    svg_parts = []
    svg = Svg(W, H, "Cumulative frame-time distribution, r10 final vs r12 final",
              "")
    svg.text(L, 26, "Cumulative frame-time distribution: r10 final vs r12 final", size=16, weight="600")
    svg.text(L, 44, "Same held-input files and start positions, captured ~5 h apart under live load: observational, not a controlled A/B.",
             cls="ink2", size=12)
    for pi, (surf, plabel) in enumerate(panels):
        x0 = L + pi * (pw + gap)
        X = lambda v: x0 + pw * min(v, xmax) / xmax  # noqa: E731
        Y = lambda p: T + ph * (1 - p)  # noqa: E731
        svg.text(x0, T - 10, plabel, size=13, weight="600")
        for t in (0, 0.25, 0.5, 0.75, 1.0):
            svg.line(x0, Y(t), x0 + pw, Y(t))
            if pi == 0:
                svg.text(x0 - 8, Y(t) + 4, f"{int(t * 100)}%", cls="ink2", size=11, anchor="end")
        for t in range(0, int(xmax) + 1, 10):
            svg.text(X(t), T + ph + 16, str(t), cls="ink2", size=11, anchor="middle")
        svg.text(x0 + pw / 2, T + ph + 34, "frame time, ms (axis clipped at 60)", cls="ink2", size=11, anchor="middle")
        svg.line(x0, T + ph, x0 + pw, T + ph, cls="axis")
        svg.line(X(16.7), T, X(16.7), T + ph, cls="ref", dash="6 4", width=1.2)
        svg.text(X(16.7) + 4, T + ph - 6, "16.7 ms", cls="ink2", size=11)
        for key, si, lab in pairs:
            ids = [rr["run_id"] for rr in sets[key]["surfaces"][surf]["runs"]]
            vals = sorted(v for rid in ids for v in samples[rid])
            n = len(vals)
            pts = [(X(0), Y(0))]
            for i, v in enumerate(vals):
                pts.append((X(v), Y(i / n)))
                pts.append((X(v), Y((i + 1) / n)))
            pts.append((X(xmax), Y(1)))
            svg.polyline(pts, si, width=2)
            s = sets[key]["surfaces"][surf]
            desc.append(f"{plabel} {lab}: n={n}, late {s['late_pct']:.1f}%, p50 {s['p50_ms']:.1f} ms, p99 {s['p99_ms']:.1f} ms, max {s['max_ms']:.1f} ms")
        ly = T + ph - 70
        for key, si, lab in pairs:
            s = sets[key]["surfaces"][surf]
            svg.marker(X(25), ly - 4, si, SHAPES[si], r=4)
            svg.text(X(25) + 9, ly, f"{lab.split(' (')[0]}: n={s['n']} · {s['late_pct']:.1f}% late · max {s['max_ms']:.0f} ms", size=11)
            ly += 18
    svg.parts[1] = svg.parts[1].replace("<desc id=\"d\"></desc>", f"<desc id=\"d\">{escape('; '.join(desc))}</desc>")
    svg.text(L, H - 12, "Cumulative share of frames at or below each frame time. Further right = slower. HWUI completion time, not display latency.",
             cls="ink2", size=11)
    svg.save(name)


def chart_startup(name):
    rows = list(csv.DictReader(open(DATA / "startup_samples.csv", newline="")))
    modes = [("verify", "verify (no AOT)"), ("speed-profile", "speed-profile"), ("speed", "speed (full AOT)")]
    W, H = 920, 300
    L, R, T, B = 150, 40, 70, 64
    pw, ph = W - L - R, H - T - B
    xmax = 2000
    X = lambda v: L + pw * v / xmax  # noqa: E731
    desc = []
    svg = Svg(W, H, "Cold start by ART compilation mode", "")
    svg.text(24, 26, "Cold start by ART compilation mode (same entry APK 67142546, 4 launches each)", size=16, weight="600")
    svg.text(24, 44, "Process-cold launches with warm disk/image cache and saved login. Early UI build (2026-10-12), before later optimisation.",
             cls="ink2", size=12)
    for t in range(0, xmax + 1, 250):
        svg.line(X(t), T, X(t), T + ph)
        svg.text(X(t), T + ph + 16, str(t), cls="ink2", size=11, anchor="middle")
    svg.text(L + pw / 2, T + ph + 34, "milliseconds from process start", cls="ink2", size=11, anchor="middle")
    svg.line(X(1500), T - 6, X(1500), T + ph, cls="ref", dash="6 4", width=1.2)
    svg.text(X(1500) + 4, T - 8, "1.5 s target", cls="ink2", size=11)
    band = ph / len(modes)
    for i, (m, lab) in enumerate(modes):
        cy = T + band * (i + 0.5)
        svg.text(L - 10, cy + 4, lab, size=12.5, anchor="end")
        rs = [r for r in rows if r["campaign"] == "r4-compiler-modes" and r["compile_state"] == m]
        hr = sorted(int(r["home_ready_ms"]) for r in rs)
        am = sorted(int(r["am_total_ms"]) for r in rs)
        for v in am:
            svg.marker(X(v), cy + 7, 2, "diamond", r=4.5)
        for v in hr:
            svg.marker(X(v), cy - 7, 1, "circle", r=4.5)
        med = lambda a: (a[1] + a[2]) / 2  # noqa: E731
        if max(hr) > 1400:
            svg.text(X(min(hr)) - 10, cy - 3, f"Home ready median {med(hr):g} ms", size=11, anchor="end")
        else:
            svg.text(X(max(hr)) + 10, cy - 3, f"Home ready median {med(hr):g} ms", size=11)
        svg.text(X(max(am)) + 10, cy + 11, f"am start {med(am):g} ms", cls="ink2", size=11)
        desc.append(f"{lab}: HOME_READY {hr}, am start TotalTime {am}")
    svg.marker(24 + 4, H - 16, 1, "circle", r=4)
    svg.text(24 + 12, H - 12, "MARQUEE_HOME_READY (hero + first-row art drawn)", size=11)
    svg.marker(400, H - 16, 2, "diamond", r=4)
    svg.text(408, H - 12, "am start -W TotalTime (first frame of the activity)", size=11)
    svg.parts[1] = svg.parts[1].replace("<desc id=\"d\"></desc>", f"<desc id=\"d\">{escape('; '.join(desc))}</desc>")
    svg.save(name)


PLAYBACK_ROWS = [  # (case_id, session, event, label, round series)
    ("qa-r9/mediaD-ac3", "0", "start", "media-D start · 1920x800 H.264 direct", 1),
    ("qa-r9/mediaE", "0", "start", "media-E start · HEVC SDR direct", 1),
    ("qa-r9/mediaB-start", "0", "start", "media-B start · 3164x2152 HEVC HDR direct", 1),
    ("qa-r9/mediaG-intro", "0", "start", "media-G start · H.264 episode direct", 1),
    ("qa-r9/mediaF-aac", "0", "start", "media-F start · HEVC SDR episode direct", 1),
    ("qa-r9/mediaA-start", "0", "start", "media-A start · 4K DV, video copy + audio convert", 1),
    ("qa-r9/mediaB-seek", "0", "seek-in-place", "media-B seek (in place)", 1),
    ("r10p/final-mediaA-start", "0", "start", "media-A start", 2),
    ("r10p/final-mediaA-resume", "0", "resume", "media-A resume", 2),
    ("r10p/final-mediaA-seek", "1", "seek-restart", "media-A seek (stream restart)", 2),
    ("r10p/final-mediaC-srt", "0", "resume", "media-C resume · HEVC SDR + SRT", 2),
    ("r12p/mediaA-accepted", "0", "resume", "media-A resume (keyframe-snapped)", 3),
    ("r12p/mediaA-accepted", "1", "seek-restart", "media-A seek 1 (stream restart)", 3),
    ("r12p/mediaA-accepted", "2", "seek-restart", "media-A seek 2 (stream restart)", 3),
    ("r12p/mediaB-accepted", "0", "resume", "media-B resume (keyframe-snapped)", 3),
    ("r12p/mediaB-accepted", "0", "seek-in-place", "media-B seek (in place)", 3),
    ("r12p/mediaC-accepted", "0", "resume", "media-C resume", 3),
]


def chart_playback(name):
    rows = list(csv.DictReader(open(DATA / "playback_samples.csv", newline="")))
    idx = {(r["case_id"], r["session"], r["event"]): r for r in rows}
    W = 920
    rowh = 21
    L, R, T = 330, 120, 74
    H = T + rowh * len(PLAYBACK_ROWS) + 34 * 3 + 100
    xmax = 3000
    pw = W - L - R
    X = lambda v: L + pw * min(v, xmax) / xmax  # noqa: E731
    svg = Svg(W, H, "Playback time to first frame, accepted samples", "")
    svg.text(24, 26, "Playback: time to first video frame (single samples per action)", size=16, weight="600")
    svg.text(24, 44, "Start/resume: player Activity created to first rendered frame. Seek: seek committed to first new frame. Includes server work.",
             cls="ink2", size=12)
    groups = [(1, "QA r9 · 4519f3d3"), (2, "r10 player final · f0370893"), (3, "r12 final player · d2d2e752")]
    y = T + 6
    desc = []
    gridtop = y
    ypos = []
    for gi, glabel in groups:
        y += 28
        svg.text(24, y, glabel, size=12.5, weight="600")
        y += 6
        for case, sess, ev, lab, si in PLAYBACK_ROWS:
            if si != gi:
                continue
            y += rowh
            r = idx[(case, sess, ev)]
            v = int(r["seek_to_frame_ms"] or r["since_activity_ms"])
            ypos.append((y, v, si, lab, r))
    bottom = y + 10
    for t in range(0, xmax + 1, 500):
        svg.line(X(t), gridtop, X(t), bottom)
        svg.text(X(t), bottom + 16, str(t), cls="ink2", size=11, anchor="middle")
    svg.text(L + pw / 2, bottom + 34, "milliseconds", cls="ink2", size=11, anchor="middle")
    svg.line(X(2000), gridtop, X(2000), bottom, cls="ref", dash="6 4", width=1.2)
    for yy, v, si, lab, r in ypos:
        svg.text(L - 10, yy + 4, lab, size=11.5, anchor="end")
        svg.line(L, yy, X(v), yy, cls="link", width=1)
        svg.marker(X(v), yy, si, SHAPES[si], r=4.5)
        extra = ""
        if r["landing_delta_ms"] not in ("", "0"):
            dm = int(r['landing_delta_ms'])
            extra = f"  lands {dm:+d} ms from target" if abs(dm) < 1000 else f"  lands {dm / 1000:+.1f} s from target"
        svg.text(X(v) + 9, yy + 4, f"{v} ms{extra}", cls="ink2", size=11)
        desc.append(f"{lab} ({r['apk_sha256'][:8]}): {v} ms{extra}")
    svg.text(24, H - 28, "Off-scale (not drawn): r9 media-A seek re-open, prepare to frame 8734 ms (player run) and 9503 ms (QA run). n = 1 per row.",
             cls="ink2", size=11)
    svg.text(24, H - 12, "Dashed line: 2 s. Different rounds used different seek targets and builds; rows are not matched A/B pairs.", cls="ink2", size=11)
    svg.parts[1] = svg.parts[1].replace("<desc id=\"d\"></desc>", f"<desc id=\"d\">{escape('; '.join(desc))}</desc>")
    svg.save(name)


def chart_controlled(name, summary):
    rows = summary["controlled"]
    W = 960
    rowh = 50
    L, PR, T = 200, 560, 80
    H = T + rowh * len(rows) + 70
    xmax = 90
    pw = PR - L
    X = lambda v: L + pw * v / xmax  # noqa: E731
    svg = Svg(W, H, "Same-APK A/B controls: share of late frames", "")
    svg.text(24, 26, "Same-APK A/B controls: share of frames over 16.7 ms", size=16, weight="600")
    svg.text(24, 44, "Each pair: one APK, a runtime flag switched, captured minutes apart on the same path. Short sequential samples.",
             cls="ink2", size=12)
    top, bottom = T, T + rowh * len(rows)
    for t in range(0, xmax + 1, 10):
        svg.line(X(t), top - 6, X(t), bottom)
        svg.text(X(t), bottom + 16, str(t), cls="ink2", size=11, anchor="middle")
    svg.text(L + pw / 2, bottom + 34, "% of frames over 16.7 ms", cls="ink2", size=11, anchor="middle")
    desc = []
    for i, c in enumerate(rows):
        cy = T + rowh * (i + 0.5)
        a, b = c["a"], c["b"]
        svg.text(L - 12, cy - 2, f"{c['surface']}: {c['key'].split('-', 1)[1].replace('-', ' ')}", size=12, anchor="end", weight="600")
        svg.text(L - 12, cy + 13, f"APK {c['apk']}", cls="ink2", size=10.5, anchor="end")
        svg.line(X(a["late_pct"]), cy, X(b["late_pct"]), cy, cls="link", width=3)
        svg.marker(X(a["late_pct"]), cy, 1, "circle", r=5)
        svg.marker(X(b["late_pct"]), cy, 2, "diamond", r=5)
        for j, (d, lab, si, shape) in enumerate(((a, c["a_label"], 1, "circle"), (b, c["b_label"], 2, "diamond"))):
            ty = cy - 4 + j * 16
            svg.marker(PR + 30, ty - 4, si, shape, r=4)
            svg.text(PR + 40, ty, f"{lab}: {d['late_pct']:.1f}% late · p99 {d['p99_ms']:.0f} ms · n={d['n']}", size=11.5)
        desc.append(f"{c['surface']} {c['key']} ({c['apk']}): {c['a_label']} {a['late_pct']:.1f}% late, p99 {a['p99_ms']:.1f} ms, n={a['n']}; "
                    f"{c['b_label']} {b['late_pct']:.1f}% late, p99 {b['p99_ms']:.1f} ms, n={b['n']}")
    svg.marker(24 + 5, H - 16, 1, "circle", r=4)
    svg.text(24 + 13, H - 12, "control / original variant", size=11)
    svg.marker(240, H - 16, 2, "diamond", r=4)
    svg.text(248, H - 12, "alternative variant", size=11)
    svg.parts[1] = svg.parts[1].replace("<desc id=\"d\"></desc>", f"<desc id=\"d\">{escape('; '.join(desc))}</desc>")
    svg.save(name)


def main():
    CHARTS.mkdir(parents=True, exist_ok=True)
    s = load_summary()
    chart_by_build(s, "late_pct", "late-frames-by-build.svg", "Share of frames over 16.7 ms, by build",
                   "% of frames over 16.7 ms", 80, 5, "5% target")
    chart_by_build(s, "p99_ms", "p99-by-build.svg", "99th-percentile frame time, by build",
                   "p99 frame time, ms", 100, 33, "33 ms target")
    chart_ecdf("frame-time-cdf-r10-vs-r12.svg")
    chart_startup("startup-compiler-modes.svg")
    chart_playback("playback-first-frame.svg")
    chart_controlled("controlled-ab.svg", s)
    print("charts written to", CHARTS)


if __name__ == "__main__":
    main()
