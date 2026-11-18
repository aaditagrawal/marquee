from marquee_bench.markers import parse_playback, parse_startup

STARTUP = """\
10-01 12:00:00.100  4000  4000 I Marquee : MARQUEE_STARTUP application 80
10-01 12:00:00.600  4000  4000 I Marquee : MARQUEE_STARTUP snapshot-read 547
10-01 12:00:00.900  4000  4000 I Marquee : MARQUEE_HOME_READY 873 cache=true launch=cold cards=4
10-01 12:00:00.900  4000  4000 I Marquee : MARQUEE_HOME_READY 873 cache=true launch=cold cards=4
"""

PLAY = """\
10-01 12:01:00.000  5000  5000 I MarqueePlayer: PREPARE item=0123456789abcdef0123456789abcdef method=Transcode copy=true mode=Direct reasons=[AudioCodecNotSupported] start=698239 requested=701945
10-01 12:01:00.900  5000  5001 I MarqueePlayer: SYNC_FRAME requested=701945 landed=698239 delta=-3706 ttff=1005 prerollMs=0
10-01 12:01:01.000  5000  5000 I MarqueePlayer: MARQUEE_TTFF 1014 prepare=769 item=0123456789abcdef0123456789abcdef
10-01 12:01:01.000  5000  5000 I MarqueePlayer: TTFF ms=769 sinceIntent=1014 method=Transcode mode=Direct
10-01 12:01:01.010  5000  5000 I MarqueePlayer: AUDIO_TTFF sinceIntent=971 prepare=726
10-01 12:01:09.000  5000  5000 I MarqueePlayer: PREPARE item=0123456789abcdef0123456789abcdef method=Transcode copy=true mode=Direct reasons=[] start=715548 requested=715545
10-01 12:01:10.000  5000  5000 I MarqueePlayer: SEEK first_frame_ms=1936
10-01 12:01:10.000  5000  5000 I MarqueePlayer: TTFF ms=1815 sinceIntent=10999 method=Transcode mode=Direct
10-01 12:01:11.000  5000  5000 E MarqueePlayer: FAIL something
"""


def test_startup_dedup_and_stages():
    procs = parse_startup(STARTUP)
    assert len(procs) == 1
    assert procs[0]["home_ready"] == [{"ms": 873, "cache": True, "launch": "cold"}]
    assert procs[0]["stages"] == {"application": 80, "snapshot-read": 547}


def test_playback_sessions_and_no_ids():
    s = parse_playback(PLAY)
    assert len(s) == 2
    assert s[0]["since_activity_ms"] == 1014 and s[0]["prepare_to_first_frame_ms"] == 769
    assert s[0]["audio_since_activity_ms"] == 971 and s[0]["reasons"] == ["AudioCodecNotSupported"]
    assert s[0]["positions"] == [{"policy": "sync", "requested_ms": 701945, "landed_ms": 698239, "delta_ms": -3706}]
    assert s[1]["seeks_to_frame_ms"] == [1936] and s[1]["since_activity_is_cumulative"]
    assert s[1]["failure"] == "player reported failure"
    assert "0123456789abcdef" not in repr(s)


def test_app_sources_still_emit_the_parsed_markers():
    """The parser above depends on these exact log prefixes in the app (debug/profile builds only)."""
    import pathlib

    root = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "java" / "com" / "mav" / "marquee"
    if not root.is_dir():  # bench/ copied without the app
        import pytest
        pytest.skip("app sources not present")
    src = "\n".join(p.read_text(encoding="utf-8") for p in root.rglob("*.kt"))
    for needle in ('"MARQUEE_STARTUP $', '"MARQUEE_HOME_READY $ms cache=$', 'launch=$', '"PREPARE item=',
                   ' method=$', ' copy=$', ' reasons=$', ' requested=$', '"TTFF ms=$', ' sinceIntent=$',
                   '"AUDIO_TTFF sinceIntent=', '"SEEK first_frame_ms=', '"EXACT_FRAME"', '"SYNC_FRAME"',
                   ' landed=$', ' delta=$', '"FAIL $', 'const val TAG = "MarqueePlayer"',
                   'Log.i("Marquee", "MARQUEE_STARTUP', 'Log.i("MarqueePlayer"'):
        assert needle in src, needle
