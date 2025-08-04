"""Create simple SVG maps with wind direction arrows."""

import math


def create_wind_map(lat: float, lon: float, direction: float, speed: float, output_path: str, size: int = 200) -> str:
    """Render a minimal wind map as an SVG file.

    The function draws a line representing wind direction and uses
    wind speed to scale the arrow length.

    Args:
        lat: Latitude in degrees (informational only).
        lon: Longitude in degrees (informational only).
        direction: Wind direction in degrees where 0 means north.
        speed: Wind speed used for arrow length.
        output_path: Path to write the SVG image.
        size: Width and height of the image in pixels.

    Returns:
        The path to the written SVG file.
    """
    center = size // 2
    length = min(center, int(speed * 2))
    angle = math.radians(270 - direction)
    end_x = center + length * math.cos(angle)
    end_y = center + length * math.sin(angle)
    svg = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}">',
        f'<circle cx="{center}" cy="{center}" r="4" fill="blue" />',
        f'<line x1="{center}" y1="{center}" x2="{end_x}" y2="{end_y}" stroke="red" stroke-width="2" />',
        "</svg>",
    ]
    with open(output_path, "w", encoding="utf-8") as file:
        file.write("".join(svg))
    return output_path
