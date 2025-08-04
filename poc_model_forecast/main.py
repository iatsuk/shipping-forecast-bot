"""Proof of concept application for rendering simple weather forecasts."""

import sys
from pathlib import Path

if __package__ is None or __package__ == "":  # pragma: no cover - script mode
    sys.path.append(str(Path(__file__).resolve().parents[1]))
    from forecast import fetch_forecast  # type: ignore
    from svg_map import create_wind_map  # type: ignore
    from svg_table import render_forecast_table  # type: ignore
else:  # pragma: no cover
    from .forecast import fetch_forecast
    from .svg_map import create_wind_map
    from .svg_table import render_forecast_table


def find_first_valid_record(records):
    """Return first record containing wind and direction values.

    Args:
        records: List of forecast records each holding ``wind`` and ``direction``.

    Returns:
        The first record with both fields not ``None``. If none are valid,
        the original first record is returned.
    """
    for record in records:
        if record.get("wind") is not None and record.get("direction") is not None:
            return record
    return records[0]


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
    first = find_first_valid_record(forecast[models[0]])
    create_wind_map(lat, lon, first["direction"], first["wind"], "wind_map.svg")
    render_forecast_table(forecast, "forecast_table.svg")


if __name__ == "__main__":
    main()
