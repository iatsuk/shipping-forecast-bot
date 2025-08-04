def test_create_wind_map_creates_file(tmp_path, poc):
    output = tmp_path / "map.svg"
    poc.create_wind_map(0.0, 0.0, 45.0, 10.0, str(output))
    assert output.exists()
    content = output.read_text()
    assert "<rect" in content and content.count("<line") >= 2


def test_create_wind_map_handles_missing_values(tmp_path, poc):
    output = tmp_path / "map.svg"
    poc.create_wind_map(0.0, 0.0, None, None, str(output))
    assert output.exists()
    content = output.read_text()
    assert "<svg" in content and "<line" in content
