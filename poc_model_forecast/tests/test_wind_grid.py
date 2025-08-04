from datetime import datetime, timezone
import types
from unittest.mock import patch


def _mock_response(data):
    mock = types.SimpleNamespace()
    mock.json = lambda: data
    mock.raise_for_status = lambda: None
    return mock


def test_fetch_noon_wind_returns_values(poc):
    data = {
        "hourly": {
            "time": ["2024-01-01T00:00", "2024-01-01T12:00"],
            "windspeed_10m": [1.0, 2.0],
            "winddirection_10m": [90.0, 100.0],
        }
    }
    with patch("requests.get", return_value=_mock_response(data)):
        with patch.object(poc, "next_noon", return_value=datetime(2024, 1, 1, 12, tzinfo=timezone.utc)):
            speed, direction = poc.fetch_noon_wind(0.0, 0.0, "gfs")
    assert speed == 2.0 and direction == 100.0


def test_fetch_wind_grid_shape(poc):
    def side_effect(la, lo, model):
        return la + lo, la - lo

    with patch.object(poc, "fetch_noon_wind", side_effect=side_effect) as mock_fn:
        lats, lons, speeds, directions = poc.fetch_wind_grid(0.0, 0.0, "gfs", size=3)

    assert lats == [-1.0, 0.0, 1.0]
    assert lons == [-1.0, 0.0, 1.0]
    assert speeds[1][1] == 0.0 and directions[1][1] == 0.0
    assert mock_fn.call_count == 9

