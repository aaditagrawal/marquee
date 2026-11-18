"""The committed benchmark dataset must validate and summary.json must be fresh."""
import json

import summarize
import validate_data


def test_committed_data_validates():
    assert validate_data.validate(validate_data.DEFAULT) == []


def test_summary_matches_documented_final_numbers():
    s = summarize.build()
    final = next(b for b in s["build_sets"] if b["key"] == "r12-final")["surfaces"]
    assert final["home"]["n"] == 272 and round(final["home"]["late_pct"], 2) == 2.94
    assert final["movies"]["n"] == 234 and round(final["movies"]["late_pct"], 2) == 2.99
    assert final["osd"] is None  # never measured on the final UI build
    json.dumps(s)
