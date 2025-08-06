import pytest


def test_create_wind_map_creates_file(tmp_path, poc, monkeypatch):
    if poc.ccrs is None:
        pytest.skip("cartopy not installed")

    def fake_grid(lat, lon, model, size):
        lats = [lat - 1 + 2 * i / (size - 1) for i in range(size)]
        lons = [lon - 1 + 2 * j / (size - 1) for j in range(size)]
        speeds = [[10.0 for _ in range(size)] for _ in range(size)]
        dirs = [[90.0 for _ in range(size)] for _ in range(size)]
        return lats, lons, speeds, dirs

    monkeypatch.setattr(poc, "fetch_wind_grid", fake_grid)
    output = tmp_path / "map.png"
    poc.create_wind_map(0.0, 0.0, "gfs", str(output), size=3)
    assert output.exists() and output.stat().st_size > 0


def test_create_wind_map_requires_cartopy(tmp_path, poc, monkeypatch):
    if poc.ccrs is not None:
        pytest.skip("cartopy installed")

    with pytest.raises(RuntimeError):
        poc.create_wind_map(0.0, 0.0, "gfs", str(tmp_path / "map.png"), size=3)

