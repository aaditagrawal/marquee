#!/usr/bin/env python3
"""Regenerate or check docs/media/settings-licences-closeup.png.

The close-up stacks the same 1160x420 px region (x 680-1840, y 620-1040 of the 1920x1080 render)
from two Settings renders, pasted 1:1 inside the cyan outlines:

- BEFORE panel at (24, 80):  docs/media/settings-licences-before.png (fixed; previous code)
- AFTER panel at (24, 580):  app/src/test/screenshots/settings-end.png (current golden)

Labels, outlines and the BEFORE panel are kept from the committed image; --write only replaces the
AFTER panel, so re-run it after re-recording the settings-end golden (for example after changing
source_repository_url). --check (the default) exits non-zero if either panel differs from its source.

    uv run --no-project --with pillow python tools/licences_closeup.py --write
    uv run --no-project --with pillow python tools/licences_closeup.py --check
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
CLOSEUP = ROOT / "docs/media/settings-licences-closeup.png"
BEFORE = ROOT / "docs/media/settings-licences-before.png"
AFTER = ROOT / "app/src/test/screenshots/settings-end.png"
REGION = (680, 620, 1840, 1040)  # left, top, right, bottom in the 1920x1080 renders
PANELS = {BEFORE: (24, 80), AFTER: (24, 580)}


def crop(path: Path) -> Image.Image:
    img = Image.open(path).convert("RGB")
    if img.size != (1920, 1080):
        sys.exit(f"{path.relative_to(ROOT)}: expected 1920x1080, got {img.size[0]}x{img.size[1]}")
    return img.crop(REGION)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="replace the AFTER panel from the current golden")
    mode.add_argument("--check", action="store_true", help="verify both panels (default)")
    args = ap.parse_args()

    closeup = Image.open(CLOSEUP).convert("RGB")
    if args.write:
        closeup.paste(crop(AFTER), PANELS[AFTER])
        closeup.save(CLOSEUP, optimize=True)  # Pillow writes no text/time chunks
        print(f"wrote {CLOSEUP.relative_to(ROOT)}")
    w, h = REGION[2] - REGION[0], REGION[3] - REGION[1]
    bad = []
    for src, (x, y) in PANELS.items():
        if closeup.crop((x, y, x + w, y + h)).tobytes() != crop(src).tobytes():
            bad.append(src.relative_to(ROOT))
    if bad:
        for b in bad:
            print(f"close-up panel differs from {b}", file=sys.stderr)
        print("run: uv run --no-project --with pillow python tools/licences_closeup.py --write", file=sys.stderr)
        return 1
    print("settings-licences-closeup.png matches its sources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
