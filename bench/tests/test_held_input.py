import json

import pytest

from marquee_bench import held_input


def test_parse_path():
    assert held_input.parse_path("right:1700, down:760") == [("right", 1700), ("down", 760)]
    for bad in ("", "sideways:10", "up:0", "up:99999", "up:x"):
        with pytest.raises(ValueError):
            held_input.parse_path(bad)


def test_generate_is_valid_hid_json():
    legs = [("right", 1000), ("left", 500)]
    events = [json.loads(line) for line in held_input.generate(legs).splitlines()]
    assert events[0]["command"] == "register" and events[0]["bus"] == "usb"
    reports = [e["report"] for e in events if e["command"] == "report"]
    assert reports == [[0, 0, 79, 0, 0, 0, 0, 0], [0] * 8, [0, 0, 80, 0, 0, 0, 0, 0], [0] * 8]
    assert held_input.duration_ms(legs) == 700 + 1120 + 620 + 400


def test_reference_paths_match_reference_protocol():
    # Home path used by every r9-r12 reference capture (byte-identical held files).
    assert held_input.REFERENCE_PATHS["home"] == [("right", 1700), ("down", 760), ("left", 1700),
                                                  ("up", 760), ("right", 1700), ("down", 760)]
    assert len(held_input.REFERENCE_PATHS["movies"]) == 9
