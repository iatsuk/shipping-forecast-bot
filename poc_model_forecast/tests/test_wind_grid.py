from datetime import datetime
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
        with patch.object(poc, "next_noon", return_value=datetime(2024, 1, 1, 12)):
            speed, direction = poc.fetch_noon_wind(0.0, 0.0, "gfs")
    assert speed == 2.0 and direction == 100.0


def test_fetch_wind_grid_shape(poc):
    data = {
        "latitude": [-1.0, 0.0, 1.0],
        "longitude": [-1.0, 0.0, 1.0],
        "hourly": {
            "time": ["2024-01-01T00:00", "2024-01-01T12:00"],
            "windspeed_10m": [
                [[0.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 0.0]],
                [[0.0, 0.0, 0.0], [0.0, 2.0, 0.0], [0.0, 0.0, 0.0]],
            ],
            "winddirection_10m": [
                [[0.0, 0.0, 0.0], [0.0, 90.0, 0.0], [0.0, 0.0, 0.0]],
                [[0.0, 0.0, 0.0], [0.0, 100.0, 0.0], [0.0, 0.0, 0.0]],
            ],
        },
    }
    with patch("requests.get", return_value=_mock_response(data)) as mock_get:
        with patch.object(poc, "next_noon", return_value=datetime(2024, 1, 1, 12)):
            lats, lons, speeds, directions = poc.fetch_wind_grid(0.0, 0.0, "gfs", size=3)
    params = mock_get.call_args.kwargs["params"]
    assert params["latitude_min"] == -1.0 and params["latitude_max"] == 1.0
    assert len(lats) == 3 and len(lons) == 3
    assert speeds[1][1] == 2.0 and directions[1][1] == 100.0
    assert mock_get.call_count == 1

