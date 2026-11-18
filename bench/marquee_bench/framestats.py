"""Parse ``dumpsys gfxinfo <package> framestats`` output.

The HWUI frame-stats ring buffer only holds the most recent ~120 frames, so a
capture polls it repeatedly while input is driven and concatenates the dumps.
This parser:

* finds every ``Flags,IntendedVsync,...`` header and the numeric rows after it;
* skips rows whose column count does not match the header;
* keeps only ``Flags == 0`` rows (non-zero flags mark frames HWUI itself
  excludes from jank accounting, e.g. the first frame after a window change);
* rejects incomplete rows (``FrameCompleted`` missing, zero or ``LONG_MAX``);
* optionally restricts to an ``IntendedVsync`` window ``[lo_ns, hi_ns]``
  (device ``CLOCK_MONOTONIC`` nanoseconds; ``/proc/uptime`` on Android 11 is
  close enough to bracket an input window, see bench/README.md);
* de-duplicates rows by ``IntendedVsync`` (the same frame appears in several
  overlapping polls; the last copy wins, which is identical in practice).

Durations are reported in milliseconds. ``total`` is
``FrameCompleted - IntendedVsync``: HWUI's own completion time for the frame.
It is **not** physical display latency, input-to-photon latency or a
presentation (missed-refresh) count.
"""
from __future__ import annotations

import re
from typing import Iterable

from .stats import summarize

LONG_MAX = 2**63 - 1
_HEADER_PREFIX = "Flags,IntendedVsync,"
_ROW = re.compile(r"^\d+,\d+,")

#: name -> (start column, end column); all durations are end - start.
STAGES = {
    "total": ("IntendedVsync", "FrameCompleted"),
    "main_until_sync": ("IntendedVsync", "SyncQueued"),
    "traversal_until_draw": ("PerformTraversalsStart", "DrawStart"),
    "renderthread_commands": ("IssueDrawCommandsStart", "SwapBuffers"),
    "gpu_ready": ("IntendedVsync", "GpuCompleted"),
}


def parse(raw: str, lo_ns: int = 0, hi_ns: int = LONG_MAX) -> list[dict]:
    """Return de-duplicated, valid frame rows sorted by IntendedVsync."""
    header: list[str] | None = None
    rows: dict[int, dict] = {}
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith(_HEADER_PREFIX):
            header = line.rstrip(",").split(",")
            continue
        if header is None or not _ROW.match(line):
            continue
        cols = line.rstrip(",").split(",")
        if len(cols) != len(header):
            continue
        try:
            row = dict(zip(header, (int(c) for c in cols)))
        except ValueError:
            continue
        start, end = row.get("IntendedVsync", 0), row.get("FrameCompleted", 0)
        if row.get("Flags") != 0:
            continue
        if not (lo_ns <= start <= hi_ns):
            continue
        if not (0 < start < end < LONG_MAX):
            continue
        rows[start] = row
    return [rows[k] for k in sorted(rows)]


def stage_ms(row: dict, stage: str) -> float | None:
    """Duration of one stage for one row, or None if the endpoints are invalid."""
    a, b = STAGES[stage]
    if a not in row or b not in row:
        return None
    start, end = row[a], row[b]
    if not (0 < start <= end < LONG_MAX):
        return None
    return (end - start) / 1e6


def durations(rows: Iterable[dict], stage: str = "total") -> list[float]:
    out = []
    for row in rows:
        v = stage_ms(row, stage)
        if v is not None:
            out.append(v)
    return out


def summarize_rows(rows: list[dict]) -> dict:
    """Per-stage summaries (see :data:`STAGES`)."""
    return {name: summarize(durations(rows, name)) for name in STAGES}
