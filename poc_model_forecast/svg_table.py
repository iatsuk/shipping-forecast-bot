"""Render forecast information into a simple SVG table."""

from typing import Dict, List, Any


def render_forecast_table(forecasts: Dict[str, List[Dict[str, Any]]], output_path: str) -> str:
    """Write forecast data to an SVG file containing plain text rows.

    Args:
        forecasts: Mapping of model names to lists of hourly records.
        output_path: File path for the generated SVG image.

    Returns:
        The path to the written SVG file.
    """
    model_names = list(forecasts.keys())
    header = ["time"]
    for name in model_names:
        header.extend([f"{name} wind", f"{name} gust"])
    lines = [" ".join(header)]
    hours = len(next(iter(forecasts.values())))
    for i in range(min(48, hours)):
        row = [forecasts[model_names[0]][i]["time"]]
        for name in model_names:
            wind = forecasts[name][i]["wind"]
            gust = forecasts[name][i]["gust"]
            row.append(str(wind) if wind is not None else "-")
            row.append(str(gust) if gust is not None else "-")
        lines.append(" ".join(row))
    svg_lines = []
    for idx, text in enumerate(lines, start=1):
        svg_lines.append(f'<text x="0" y="{15 * idx}" font-size="12">{text}</text>')
    height = 15 * (len(lines) + 1)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="900" height="{height}">' + "".join(svg_lines) + "</svg>"
    with open(output_path, "w", encoding="utf-8") as file:
        file.write(svg)
    return output_path
