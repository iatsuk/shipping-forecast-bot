"""Utilities for downloading weather forecasts from Open-Meteo."""

from typing import Dict, List, Any
import requests


def fetch_forecast(lat: float, lon: float, models: List[str]) -> Dict[str, List[Dict[str, Any]]]:
    """Fetch 48 hour wind forecasts for several weather models.

    Args:
        lat: Latitude in decimal degrees.
        lon: Longitude in decimal degrees.
        models: List of model identifiers supported by Open-Meteo.

    Returns:
        A dictionary mapping each model name to a list of hourly records.
        Each record contains ``time``, ``wind``, ``gust`` and ``direction``.
    """
    result: Dict[str, List[Dict[str, Any]]] = {}
    base_url = "https://api.open-meteo.com/v1/forecast"
    params = {
        "latitude": lat,
        "longitude": lon,
        "hourly": "windspeed_10m,windgusts_10m,winddirection_10m",
        "forecast_days": 2,
    }
    for model in models:
        params["models"] = model
        response = requests.get(base_url, params=params, timeout=10)
        response.raise_for_status()
        hourly = response.json()["hourly"]
        records: List[Dict[str, Any]] = []
        for i, ts in enumerate(hourly["time"][:48]):
            records.append(
                {
                    "time": ts,
                    "wind": hourly["windspeed_10m"][i],
                    "gust": hourly["windgusts_10m"][i],
                    "direction": hourly["winddirection_10m"][i],
                }
            )
        result[model] = records
    return result
