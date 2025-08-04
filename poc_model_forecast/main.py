"""Proof of concept application for rendering simple weather forecasts."""

import sys
from forecast import fetch_forecast
from svg_map import create_wind_map
from svg_table import render_forecast_table


def main() -> None:
    """Entry point for manual testing.

    Usage:
        python main.py <lat> <lon>
    The script downloads forecasts for two models and writes two SVG files:
    ``wind_map.svg`` and ``forecast_table.svg``.
    """
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python main.py <lat> <lon>")
    lat = float(sys.argv[1])
    lon = float(sys.argv[2])
    models = ["ecmwf_ifs04", "icon_seamless"]
    forecast = fetch_forecast(lat, lon, models)
    first = forecast[models[0]][0]
    create_wind_map(lat, lon, first["direction"], first["wind"], "wind_map.svg")
    render_forecast_table(forecast, "forecast_table.svg")


if __name__ == "__main__":
    main()
