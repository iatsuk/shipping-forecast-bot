"""SVG utilities for displaying wind direction and speed."""

from typing import List
import math


def speed_to_color(speed: float) -> str:
    """Map wind speed to a simple blue→red color scale.

    Speeds of 0 knots are blue and 60 knots or greater are red.
    """
    ratio = min(max(speed / 60.0, 0.0), 1.0)
    red = int(255 * ratio)
    blue = int(255 * (1 - ratio))
    return f"#{red:02x}00{blue:02x}"


def wind_barb_paths(
    cx: float,
    cy: float,
    direction: float,
    speed: float,
    length: float,
    stroke: str,
) -> List[str]:
    """Create SVG elements representing a meteorological wind barb."""
    angle = math.radians(270 - direction)
    end_x = cx + length * math.cos(angle)
    end_y = cy + length * math.sin(angle)
    elems: List[str] = [
        f'<line x1="{cx}" y1="{cy}" x2="{end_x}" y2="{end_y}" '
        f'stroke="{stroke}" stroke-width="2" />'
    ]
    barb_angle = angle + math.pi / 2
    spacing = 10
    x, y = end_x, end_y
    speed_kn = int(round(speed))

    def add_barb(x0: float, y0: float, size: float) -> None:
        x1 = x0 + size * math.cos(barb_angle)
        y1 = y0 + size * math.sin(barb_angle)
        elems.append(
            f'<line x1="{x0}" y1="{y0}" x2="{x1}" y2="{y1}" stroke="{stroke}" stroke-width="2" />'
        )

    while speed_kn >= 50:
        x2 = x - spacing * math.cos(angle)
        y2 = y - spacing * math.sin(angle)
        x3 = x2 + 12 * math.cos(barb_angle)
        y3 = y2 + 12 * math.sin(barb_angle)
        elems.append(
            f'<polygon points="{x},{y} {x2},{y2} {x3},{y3}" fill="{stroke}" />'
        )
        x, y = x2, y2
        speed_kn -= 50
    while speed_kn >= 10:
        add_barb(x, y, 10)
        x -= spacing * math.cos(angle)
        y -= spacing * math.sin(angle)
        speed_kn -= 10
    while speed_kn >= 5:
        add_barb(x, y, 5)
        x -= spacing * math.cos(angle)
        y -= spacing * math.sin(angle)
        speed_kn -= 5
    return elems


def create_wind_map(
    lat: float,
    lon: float,
    direction: float | None,
    speed: float | None,
    output_path: str,
    size: int = 200,
) -> str:
    """Render a minimal map with a meteorological wind barb."""
    speed_val = float(speed) if speed is not None else 0.0
    dir_val = float(direction) if direction is not None else 0.0
    center = size // 2
    stroke = speed_to_color(speed_val)
    length = size // 3
    elements: List[str] = [
        f'<rect width="{size}" height="{size}" fill="#e6f2ff" />',
        f'<line x1="0" y1="{center}" x2="{size}" y2="{center}" stroke="#888" />',
    ]
    elements.extend(wind_barb_paths(center, center, dir_val, speed_val, length, stroke))
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}">' \
        + "".join(elements) + "</svg>"
    )
    with open(output_path, "w", encoding="utf-8") as file:
        file.write(svg)
    return output_path
