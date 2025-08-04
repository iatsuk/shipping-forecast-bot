import sys
import pathlib

sys.path.append(str(pathlib.Path(__file__).resolve().parents[2]))

from poc_model_forecast.svg_map import create_wind_map


def test_create_wind_map_creates_file(tmp_path):
    output = tmp_path / "map.svg"
    create_wind_map(0.0, 0.0, 45.0, 10.0, str(output))
    assert output.exists()
    content = output.read_text()
    assert "<svg" in content and "<line" in content


def test_create_wind_map_handles_missing_values(tmp_path):
    output = tmp_path / "map.svg"
    create_wind_map(0.0, 0.0, None, None, str(output))
    assert output.exists()
    content = output.read_text()
    assert "<svg" in content and "<line" in content
