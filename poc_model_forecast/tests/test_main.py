import sys
import pathlib

sys.path.append(str(pathlib.Path(__file__).resolve().parents[2]))

from poc_model_forecast.main import find_first_valid_record


def test_find_first_valid_record_returns_first_complete():
    records = [
        {"wind": None, "direction": None},
        {"wind": 5, "direction": 90},
    ]
    result = find_first_valid_record(records)
    assert result["wind"] == 5 and result["direction"] == 90


def test_find_first_valid_record_fallbacks_to_first():
    records = [{"wind": None, "direction": None}]
    result = find_first_valid_record(records)
    assert result is records[0]

