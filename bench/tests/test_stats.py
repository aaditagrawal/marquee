import pytest

from marquee_bench.stats import late_count, median, percentile, summarize


def test_nearest_rank_examples():
    v = [15, 20, 35, 40, 50]
    assert percentile(v, 0.05) == 15
    assert percentile(v, 0.30) == 20
    assert percentile(v, 0.40) == 20
    assert percentile(v, 0.50) == 35
    assert percentile(v, 1.0) == 50


def test_percentile_is_order_independent_and_observed():
    v = [3.2, 1.1, 9.9, 4.4]
    assert percentile(v, 0.5) in v
    assert percentile(v, 0.5) == percentile(sorted(v), 0.5) == 3.2


def test_percentile_empty_and_bad_fraction():
    assert percentile([], 0.5) is None
    with pytest.raises(ValueError):
        percentile([1], 0)


def test_late_is_strictly_greater_than_threshold():
    assert late_count([16.7, 16.7001, 10]) == 1


def test_median_even_and_odd():
    assert median([1, 3, 2]) == 2
    assert median([4, 1, 3, 2]) == 2.5
    assert median([]) is None


def test_summarize_fields():
    s = summarize([8, 9, 10, 12, 15, 16.7, 16.8, 20, 25, 40])
    assert s["n"] == 10 and s["p50_ms"] == 15 and s["p90_ms"] == 25 and s["p99_ms"] == 40
    assert s["late_count"] == 4 and s["late_pct"] == 40.0
    assert summarize([])["n"] == 0
