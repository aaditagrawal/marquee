"""Parse Marquee's timing log markers (``adb logcat -v threadtime``).

Startup (tag ``Marquee``)::

    MARQUEE_STARTUP <stage> <ms since process start>
    MARQUEE_HOME_READY <ms> cache=<bool> launch=<cold|warm> cards=<n>

``HOME_READY`` fires once the Home hero backdrop (and logo, if any) and every
visible first-row card have been drawn. Cold launches measure from
``Process.getStartElapsedRealtime()``; warm launches from the Activity start,
so warm values exclude the bring-to-front cost that ``am start -W`` reports.

Playback (tag ``MarqueePlayer``)::

    PREPARE item=<id> method=<DirectPlay|Transcode> copy=<bool> mode=<...> reasons=[...] start=<ms> requested=<ms>
    TTFF ms=<prepare->first frame> sinceIntent=<player Activity created->first frame> method=... mode=...
    AUDIO_TTFF sinceIntent=<ms> prepare=<ms>
    SEEK first_frame_ms=<seek committed->first new frame>
    SYNC_FRAME|EXACT_FRAME requested=<ms> landed=<ms> delta=<ms> ttff=<ms> ...

``sinceIntent`` is measured from the player Activity's ``onCreate``; a later
PREPARE in the same Activity (a seek that restarts the stream, or a track
change) reports a *cumulative* sinceIntent, which is not a latency. Use
``prepare_to_first_frame_ms`` / ``seek_to_frame_ms`` for those.

Item IDs are never returned: they identify a private library.
"""
from __future__ import annotations

import re
from typing import Iterable

_LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+\w\s+(\w+)\s*:\s+(.*)$")
_KV = re.compile(r"(\w+)=(-?\w+|\[[^\]]*\])")


def _kv(msg: str) -> dict[str, str]:
    return {k: v for k, v in _KV.findall(msg)}


def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def iter_lines(raw: str | Iterable[str]):
    lines = raw.splitlines() if isinstance(raw, str) else raw
    seen = set()
    for line in lines:
        m = _LINE.match(line.strip())
        if not m:
            continue
        key = m.groups()
        if key in seen:  # overlapping logcat dumps repeat lines
            continue
        seen.add(key)
        yield {"time": m.group(1), "pid": m.group(2), "tag": m.group(4), "msg": m.group(5)}


def parse_startup(raw) -> list[dict]:
    """One dict per process (pid) with HOME_READY and stage timings."""
    procs: dict[str, dict] = {}
    for ln in iter_lines(raw):
        if ln["tag"] != "Marquee":
            continue
        msg = ln["msg"]
        p = procs.setdefault(ln["pid"], {"pid_seen_at": ln["time"], "stages": {}, "home_ready": []})
        m = re.match(r"MARQUEE_STARTUP ([\w-]+) (\d+)", msg)
        if m:
            p["stages"][m.group(1)] = int(m.group(2))
            continue
        m = re.match(r"MARQUEE_HOME_READY (\d+) cache=(\w+) launch=(\w+)", msg)
        if m:
            p["home_ready"].append({"ms": int(m.group(1)), "cache": m.group(2) == "true", "launch": m.group(3)})
    return [dict(v, pid_index=i) for i, v in enumerate(procs.values())]


def parse_playback(raw) -> list[dict]:
    """Group player markers into sessions (one per PREPARE)."""
    sessions: list[dict] = []
    cur = None
    for ln in iter_lines(raw):
        if ln["tag"] != "MarqueePlayer":
            continue
        msg = ln["msg"]
        if msg.startswith("PREPARE "):
            kv = _kv(msg)
            cur = {"time": ln["time"], "method": kv.get("method"), "mode": kv.get("mode"),
                   "video_copy": kv.get("copy") == "true",
                   "reasons": [r.strip() for r in kv.get("reasons", "[]").strip("[]").split(",") if r.strip()],
                   "start_ms": _int(kv.get("start")), "requested_ms": _int(kv.get("requested")),
                   "prepare_to_first_frame_ms": None, "since_activity_ms": None, "audio_since_activity_ms": None,
                   "seeks_to_frame_ms": [], "positions": [], "failure": None}
            sessions.append(cur)
        elif cur is None:
            continue
        elif msg.startswith("TTFF ms="):
            kv = _kv(msg)
            if cur["prepare_to_first_frame_ms"] is None:
                cur["prepare_to_first_frame_ms"] = _int(kv.get("ms"))
                cur["since_activity_ms"] = _int(kv.get("sinceIntent"))
        elif msg.startswith("AUDIO_TTFF"):
            if cur["audio_since_activity_ms"] is None:
                cur["audio_since_activity_ms"] = _int(_kv(msg).get("sinceIntent"))
        elif msg.startswith("SEEK first_frame_ms="):
            cur["seeks_to_frame_ms"].append(_int(msg.split("=", 1)[1]))
        elif msg.startswith(("SYNC_FRAME", "EXACT_FRAME")):
            kv = _kv(msg)
            cur["positions"].append({"policy": "exact" if msg.startswith("EXACT") else "sync",
                                     "requested_ms": _int(kv.get("requested")), "landed_ms": _int(kv.get("landed")),
                                     "delta_ms": _int(kv.get("delta"))})
        elif msg.startswith("FAIL"):
            cur["failure"] = "player reported failure"
    for i, s in enumerate(sessions):
        s["index"] = i
        s["since_activity_is_cumulative"] = i > 0
    return sessions
