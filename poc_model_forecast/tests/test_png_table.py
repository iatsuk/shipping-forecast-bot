import pytest


def test_render_forecast_table(tmp_path, poc):
    try:
        import PIL  # noqa: F401
    except Exception:
        pytest.skip("Pillow not installed")

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
    output = tmp_path / "table.png"
    poc.render_forecast_table(data, str(output))
    assert output.exists() and output.stat().st_size > 0


def test_format_forecast_lines_handles_none(poc):
    data = {"m1": [{"time": "t0", "wind": None, "gust": None}]}
    lines = poc.format_forecast_lines(data)
    assert "-" in lines[1]
    assert "None" not in lines[1]

