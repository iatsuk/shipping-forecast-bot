def test_render_forecast_table(tmp_path, poc):
    data = {
        "m1": [
            {"time": "t0", "wind": 1, "gust": 2},
            {"time": "t1", "wind": 3, "gust": 4},
        ],
        "m2": [
            {"time": "t0", "wind": 5, "gust": 6},
            {"time": "t1", "wind": 7, "gust": 8},
        ],
    }
    output = tmp_path / "table.svg"
    poc.render_forecast_table(data, str(output))
    assert output.exists()
    text = output.read_text()
    assert "m1 wind" in text and "t1" in text


def test_render_forecast_table_handles_none(tmp_path, poc):
    data = {"m1": [{"time": "t0", "wind": None, "gust": None}]}
    output = tmp_path / "table.svg"
    poc.render_forecast_table(data, str(output))
    text = output.read_text()
    assert "-" in text
    assert "None" not in text
