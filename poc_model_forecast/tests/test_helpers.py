
def test_find_first_valid_record_returns_first_complete(poc):
    records = [
        {"wind": None, "direction": None},
        {"wind": 5, "direction": 90},
    ]
    result = poc.find_first_valid_record(records)
    assert result["wind"] == 5 and result["direction"] == 90


def test_find_first_valid_record_fallbacks_to_first(poc):
    records = [{"wind": None, "direction": None}]
    result = poc.find_first_valid_record(records)
    assert result is records[0]


def test_next_noon_rolls_over(poc):
    from datetime import datetime, timezone

    morning = datetime(2024, 1, 1, 9, tzinfo=timezone.utc)
    assert poc.next_noon(morning) == datetime(2024, 1, 1, 12, tzinfo=timezone.utc)

    evening = datetime(2024, 1, 1, 13, tzinfo=timezone.utc)
    assert poc.next_noon(evening) == datetime(2024, 1, 2, 12, tzinfo=timezone.utc)


def test_get_with_retry_retries_and_sets_timeout(poc):
    import types
    from unittest.mock import patch
    from requests import Timeout

    calls = {"count": 0}

    def side_effect(*args, **kwargs):
        calls["count"] += 1
        if calls["count"] == 1:
            raise Timeout
        resp = types.SimpleNamespace(raise_for_status=lambda: None)
        return resp

    with patch("requests.get", side_effect=side_effect) as mock_get:
        poc.get_with_retry("http://example", {"a": 1})
    assert mock_get.call_count == 2
    assert mock_get.call_args.kwargs["timeout"] == 30
