#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate the dependency table in THIRD_PARTY_NOTICES.md from Gradle's resolved release classpath.

Licences are read from each artifact's Maven POM in the local Gradle cache, following parent POMs
when an artifact does not declare its own. Nothing is guessed: an artifact without a POM licence
is reported as UNKNOWN and the script exits non-zero.

Usage (from the repository root, after a build has populated the Gradle cache):

    ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath > /tmp/deps.txt
    uv run --no-project python tools/third_party_notices.py /tmp/deps.txt --write

Without --write the table is printed to stdout. With --write the block between the
THIRD-PARTY-TABLE markers in THIRD_PARTY_NOTICES.md is replaced, and the whole file is copied to
app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md so the APK carries it.

THIRD_PARTY_NOTICES.md is the source of truth for everything outside the table (the prose is
edited by hand). After editing the prose, copy it into the APK assets and check the copy:

    uv run --no-project python tools/third_party_notices.py --sync-asset
    uv run --no-project python tools/third_party_notices.py --check-asset
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"
ASSET = ROOT / "app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md"
START, END = "<!-- THIRD-PARTY-TABLE:START -->", "<!-- THIRD-PARTY-TABLE:END -->"

# Normalise the many spellings found in POMs to SPDX identifiers.
SPDX = {
    "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "apache 2.0": "Apache-2.0",
    "gnu general public license v3.0": "GPL-3.0",
}
# BOMs/platforms constrain versions but contribute no code to the APK.
PLATFORM = re.compile(r"(-bom|^compose-bom)$")
COORD = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\s:]+)(?: -> ([^\s]+))?")


def gradle_cache() -> pathlib.Path:
    home = os.environ.get("GRADLE_USER_HOME")
    return (pathlib.Path(home) if home else pathlib.Path.home() / ".gradle") / "caches/modules-2/files-2.1"


def resolved(dep_report: str) -> list[tuple[str, str, str]]:
    seen: dict[tuple[str, str], str] = {}
    for line in dep_report.splitlines():
        if "---" not in line:
            continue
        m = COORD.search(line.split("---", 1)[1])
        if not m:
            continue
        g, a, v, arrow = m.groups()
        seen[(g, a)] = (arrow or v).rstrip(")").split(" ")[0]
    return sorted((g, a, v) for (g, a), v in seen.items())


def _tag(e: ET.Element) -> str:
    return e.tag.split("}", 1)[-1]


def _child(e: ET.Element, name: str) -> ET.Element | None:
    return next((c for c in e if _tag(c) == name), None)


def _text(e: ET.Element | None) -> str:
    return (e.text or "").strip() if e is not None else ""


def pom_path(cache: pathlib.Path, g: str, a: str, v: str) -> pathlib.Path | None:
    hits = sorted((cache / g / a / v).glob("*/*.pom"))
    return hits[0] if hits else None


def licence_info(cache: pathlib.Path, g: str, a: str, v: str) -> tuple[str, str, str]:
    """Returns (spdx-or-raw licence, project url, provenance note)."""
    url, coord, hops = "", (g, a, v), 0
    while hops < 6:
        path = pom_path(cache, *coord)
        if path is None:
            return "UNKNOWN", url, f"POM not in Gradle cache: {':'.join(coord)}"
        root = ET.parse(path).getroot()
        url = url or _text(_child(root, "url"))
        lic = _child(root, "licenses")
        names = [_text(_child(l, "name")) for l in lic] if lic is not None else []
        if names:
            ids = sorted({SPDX.get(n.lower(), n) for n in names})
            note = "POM" if hops == 0 else f"parent POM {':'.join(coord)}"
            return " OR ".join(ids), url, note
        parent = _child(root, "parent")
        if parent is None:
            return "UNKNOWN", url, "no <licenses> in POM chain"
        coord = tuple(_text(_child(parent, k)) for k in ("groupId", "artifactId", "version"))  # type: ignore[assignment]
        hops += 1
    return "UNKNOWN", url, "POM parent chain too deep"


def table(rows: list[tuple[str, str, str, str, str, str]]) -> str:
    out = ["| Group | Artifact | Version | Licence (from POM) | Project URL |", "|---|---|---|---|---|"]
    for g, a, v, lic, url, note in rows:
        mark = " (version platform, no code)" if PLATFORM.search(a) else ""
        src = "" if note == "POM" else f" <sub>{note}</sub>"
        out.append(f"| `{g}` | `{a}`{mark} | {v} | {lic}{src} | {url or '—'} |")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report", type=pathlib.Path, nargs="?", help="output of :app:dependencies --configuration releaseRuntimeClasspath")
    ap.add_argument("--write", action="store_true", help="update THIRD_PARTY_NOTICES.md and the APK asset copy")
    ap.add_argument("--sync-asset", action="store_true", help="only copy THIRD_PARTY_NOTICES.md to the APK assets")
    ap.add_argument("--check-asset", action="store_true", help="exit 1 unless the APK asset copy is byte-identical")
    args = ap.parse_args()

    if args.sync_asset or args.check_asset:
        if args.sync_asset:
            ASSET.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(NOTICES, ASSET)
            print(f"Copied {NOTICES.relative_to(ROOT)} to {ASSET.relative_to(ROOT)}")
        if ASSET.is_file() and ASSET.read_bytes() == NOTICES.read_bytes():
            print("APK asset copy is byte-identical")
            return 0
        print(f"{ASSET.relative_to(ROOT)} differs from {NOTICES.relative_to(ROOT)}; run --sync-asset", file=sys.stderr)
        return 1
    if args.report is None:
        ap.error("a dependency report is required unless --sync-asset or --check-asset is given")

    cache = gradle_cache()
    deps = resolved(args.report.read_text())
    if not deps:
        print("No dependencies found in the report.", file=sys.stderr)
        return 2
    rows = [(g, a, v, *licence_info(cache, g, a, v)) for g, a, v in deps]
    body = table(rows)
    counts: dict[str, int] = {}
    for r in rows:
        counts[r[3]] = counts.get(r[3], 0) + 1
    summary = ", ".join(f"{k}: {n}" for k, n in sorted(counts.items()))
    block = f"{START}\n_{len(rows)} resolved artifacts — {summary}._\n\n{body}\n{END}"

    if args.write:
        text = NOTICES.read_text()
        if START not in text or END not in text:
            print(f"Markers not found in {NOTICES}", file=sys.stderr)
            return 2
        pre, rest = text.split(START, 1)
        _, post = rest.split(END, 1)
        NOTICES.write_text(pre + block + post)
        ASSET.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(NOTICES, ASSET)
        print(f"Updated {NOTICES.relative_to(ROOT)} and {ASSET.relative_to(ROOT)} ({len(rows)} artifacts; {summary})")
    else:
        print(block)
    unknown = [r for r in rows if r[3] == "UNKNOWN"]
    for r in unknown:
        print(f"UNKNOWN licence: {r[0]}:{r[1]}:{r[2]} ({r[5]})", file=sys.stderr)
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
