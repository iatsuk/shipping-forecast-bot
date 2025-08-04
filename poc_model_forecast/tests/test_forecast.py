import types
from unittest.mock import patch
import sys
import pathlib

sys.path.append(str(pathlib.Path(__file__).resolve().parents[2]))

from poc_model_forecast.forecast import fetch_forecast


def _mock_response(data):
    mock = types.SimpleNamespace()
    mock.json = lambda: data
    mock.raise_for_status = lambda: None
    return mock


def test_fetch_forecast_returns_data():
    data = {
        "hourly": {
            "time": ["t0", "t1"],
            "windspeed_10m": [5, 6],
            "windgusts_10m": [7, 8],
            "winddirection_10m": [90, 100],
        }
    }
    with patch(
        "poc_model_forecast.forecast.requests.get",
        return_value=_mock_response(data),
    ) as mock_get:
        result = fetch_forecast(1.0, 2.0, ["m1"])
        params = mock_get.call_args.kwargs["params"]
    assert "m1" in result
    assert result["m1"][0]["wind"] == 5
    assert len(result["m1"]) == 2
    # Verify we request knots from the API and specify the model name.
    assert params["windspeed_unit"] == "kn"
    assert params["models"] == "m1"
