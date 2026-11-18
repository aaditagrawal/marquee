"""Generate ``hid`` command files for a virtual USB keyboard.

Android's ``hid`` shell tool (``/system/bin/hid``) registers a uhid device and
replays JSON commands. A held key produces the framework's normal key-repeat
stream (same repeat delay/rate as a physical USB keyboard), which is closer to
a held remote D-pad than ``input keyevent`` loops. It is still synthetic input:
it is not a Bluetooth/IR remote and does not measure remote radio latency.

A *path* is a list of ``(direction, hold_ms)`` legs. Each leg presses the key,
holds it for ``hold_ms``, releases it and waits ``gap_ms``.
"""
from __future__ import annotations

import json

# Generic boot-protocol keyboard report descriptor (8-byte reports).
DESCRIPTOR = [5, 1, 9, 6, 161, 1, 5, 7, 25, 224, 41, 231, 21, 0, 37, 1,
              117, 1, 149, 8, 129, 2, 149, 1, 117, 8, 129, 1, 149, 6,
              117, 8, 21, 0, 37, 101, 5, 7, 25, 0, 41, 101, 129, 0, 192]
#: USB HID usage IDs for the arrow keys.
KEYS = {"right": 79, "left": 80, "down": 81, "up": 82}

#: Paths used by the reference measurements (see docs/benchmarks/README.md).
#: They are layout-specific: adapt them so the held input stays inside the
#: surface under test (for example, never scroll a grid into its header).
REFERENCE_PATHS: dict[str, list[tuple[str, int]]] = {
    "home": [("right", 1700), ("down", 760), ("left", 1700), ("up", 760),
             ("right", 1700), ("down", 760)],
    "movies": [("down", 1600), ("up", 900)] * 4 + [("down", 1100)],
    "series": [("left", 1400), ("right", 1400)] * 4,
    "search": [("right", 1700), ("left", 1700)] * 3,
    "osd": [("right", 900), ("left", 900)] * 5,
}


def parse_path(spec: str) -> list[tuple[str, int]]:
    """Parse ``"right:1700,down:760"`` into legs. Raises ValueError on bad input."""
    legs = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        key, _, ms = part.partition(":")
        key = key.strip().lower()
        if key not in KEYS:
            raise ValueError(f"unknown direction {key!r}; expected one of {sorted(KEYS)}")
        hold = int(ms)
        if not 0 < hold <= 10_000:
            raise ValueError("hold must be 1..10000 ms")
        legs.append((key, hold))
    if not legs:
        raise ValueError("empty path")
    return legs


def generate(legs: list[tuple[str, int]], *, name: str = "Benchmark Remote",
             settle_ms: int = 700, gap_ms: int = 120, tail_ms: int = 400) -> str:
    """Return newline-delimited JSON for ``hid``."""
    events: list[dict] = [
        {"id": 1, "command": "register", "name": name, "bus": "usb",
         "vid": 6353, "pid": 24066, "descriptor": DESCRIPTOR},
        {"id": 1, "command": "delay", "duration": settle_ms},
    ]
    for key, hold in legs:
        events += [
            {"id": 1, "command": "report", "report": [0, 0, KEYS[key], 0, 0, 0, 0, 0]},
            {"id": 1, "command": "delay", "duration": hold},
            {"id": 1, "command": "report", "report": [0] * 8},
            {"id": 1, "command": "delay", "duration": gap_ms},
        ]
    events.append({"id": 1, "command": "delay", "duration": tail_ms})
    return "\n".join(json.dumps(e) for e in events) + "\n"


def duration_ms(legs: list[tuple[str, int]], *, settle_ms: int = 700, gap_ms: int = 120,
                tail_ms: int = 400) -> int:
    """Nominal wall-clock duration of a generated file, excluding registration time."""
    return settle_ms + sum(h + gap_ms for _, h in legs) + tail_ms
