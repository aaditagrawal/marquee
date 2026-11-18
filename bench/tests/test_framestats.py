import pathlib

from marquee_bench import framestats

FIX = (pathlib.Path(__file__).parent / "fixtures" / "framestats_two_polls.txt").read_text()
BASE, PERIOD = 1_000_000_000_000, 16_666_667


def test_dedup_flags_incomplete_and_mismatched_rows():
    rows = framestats.parse(FIX)
    # 10 unique valid frames in the window + 1 valid frame far later; the flagged,
    # incomplete and column-mismatched rows are dropped; overlapping polls deduplicated.
    assert len(rows) == 11
    assert [r["IntendedVsync"] for r in rows] == sorted(r["IntendedVsync"] for r in rows)


def test_window_restricts_frames():
    rows = framestats.parse(FIX, BASE, BASE + 10 * PERIOD)
    assert len(rows) == 10
    s = framestats.summarize_rows(rows)["total"]
    assert s["n"] == 10
    assert s["late_count"] == 4  # 16.8, 20, 25, 40; exactly 16.7 is not late
    assert s["p50_ms"] == 15.0 and s["p99_ms"] == 40.0 and s["max_ms"] == 40.0


def test_stage_durations():
    rows = framestats.parse(FIX, BASE, BASE + 10 * PERIOD)
    s = framestats.summarize_rows(rows)
    assert s["main_until_sync"]["p50_ms"] == 4.0
    assert s["renderthread_commands"]["p50_ms"] == 3.0
    assert s["traversal_until_draw"]["n"] == 10


def test_empty_and_garbage_input():
    assert framestats.parse("") == []
    assert framestats.parse("no header\n0,1,2\n") == []
    assert framestats.summarize_rows([])["total"]["n"] == 0
