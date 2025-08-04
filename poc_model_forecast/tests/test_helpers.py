
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
