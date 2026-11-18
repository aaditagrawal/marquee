"""Small, dependency-free statistics used by every Marquee benchmark table.

Percentiles use the *nearest-rank* definition: for a sorted sample of size n,
the p-th percentile is the value at 1-based rank ceil(n * p), clamped to 1.
No interpolation is performed, so every reported percentile is an observed
sample. This matches the harness that produced the reference measurements.
"""
from __future__ import annotations

import math
from typing import Iterable, Sequence

#: HWUI work budget used by the reference measurements (one 60 Hz refresh).
LATE_THRESHOLD_MS = 16.7


def percentile(values: Sequence[float], fraction: float) -> float | None:
    """Nearest-rank percentile. ``fraction`` is in (0, 1]. Returns None if empty."""
    if not 0 < fraction <= 1:
        raise ValueError("fraction must be in (0, 1]")
    ordered = sorted(values)
    if not ordered:
        return None
    rank = max(1, math.ceil(len(ordered) * fraction))
    return ordered[rank - 1]


def median(values: Sequence[float]) -> float | None:
    """Conventional median (mean of the two middle values for even n).

    Used only for small startup/playback sample sets, where it is labelled
    "median"; frame-time tables use :func:`percentile` (p50, nearest rank).
    """
    ordered = sorted(values)
    n = len(ordered)
    if n == 0:
        return None
    mid = n // 2
    return ordered[mid] if n % 2 else (ordered[mid - 1] + ordered[mid]) / 2


def late_count(values: Iterable[float], threshold_ms: float = LATE_THRESHOLD_MS) -> int:
    """Number of samples strictly greater than ``threshold_ms``."""
    return sum(1 for v in values if v > threshold_ms)


def summarize(values: Sequence[float], threshold_ms: float = LATE_THRESHOLD_MS,
              digits: int = 4) -> dict:
    """Summary used in tables: n, p50/p90/p95/p99/max, late count and percent."""
    n = len(values)

    def r(x):
        return None if x is None else round(x, digits)

    if n == 0:
        return {"n": 0, "p50_ms": None, "p90_ms": None, "p95_ms": None, "p99_ms": None,
                "max_ms": None, "late_count": None, "late_pct": None}
    late = late_count(values, threshold_ms)
    return {
        "n": n,
        "p50_ms": r(percentile(values, 0.50)),
        "p90_ms": r(percentile(values, 0.90)),
        "p95_ms": r(percentile(values, 0.95)),
        "p99_ms": r(percentile(values, 0.99)),
        "max_ms": r(max(values)),
        "late_count": late,
        "late_pct": round(100.0 * late / n, 4),
    }
