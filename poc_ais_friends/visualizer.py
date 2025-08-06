"""
visualizer.py – Voyager background, OpenSeaMap overlay, vessel arrows
====================================================================

• Picks the first background that contains real imagery
  (Voyager → Toner → OSM).
• Draws OpenSeaMap seamarks over that background when available.
• Renders vessels as ~2 %-size arrows plus labels that avoid overlaps.

Run directly → demo_map.png appears.

Dependencies
------------
pip install staticmap Pillow requests
"""

from __future__ import annotations

import argparse
import logging
import math
from datetime import UTC, datetime
from pathlib import Path
from random import randint, random
from typing import Iterable, List, Sequence, Tuple
import numpy as np

import requests
from PIL import Image, ImageDraw, ImageFont
from staticmap import CircleMarker, StaticMap

# ------------------------------------------------------------------ #
# Force browser-like UA for *all* requests (works on any version)    #
# ------------------------------------------------------------------ #
_ORIG_REQ = requests.sessions.Session.request


def _patched_request(self, method, url, **kw):  # type: ignore[override]
    headers = kw.pop("headers", {})
    headers.setdefault(
        "User-Agent",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0 Safari/537.36",
    )
    return _ORIG_REQ(self, method, url, headers=headers, **kw)


requests.sessions.Session.request = _patched_request  # type: ignore[attr-defined]

# ------------------------------------------------------------------ #
# Optional import from project (stub fallback for demo)              #
# ------------------------------------------------------------------ #
try:
    from repository import VesselData  # type: ignore
except ImportError:  # pragma: no cover
    from typing import NamedTuple

    class VesselData(NamedTuple):
        mmsi: int
        lat: float
        lon: float
        sog: float
        cog: float
        ship_name: str | None
        call_sign: str | None
        size: str | None
        ais_class: str | None
        timestamp: datetime


_LOG = logging.getLogger("aisstream.visualizer")

# Background providers (public, no API key)
BASE_TEMPLATES = [
    "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
    "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    "https://stamen-tiles.a.ssl.fastly.net/toner/{z}/{x}/{y}.png",
]
TEMPLATE_OPENSEA = "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png"


# ------------------------------------------------------------------ #
# Helper functions                                                   #
# ------------------------------------------------------------------ #
def _bbox(
    lats: Sequence[float], lons: Sequence[float], margin_deg: float = 0.15
) -> Tuple[float, float, float, float]:
    return (
        min(lats) - margin_deg,
        max(lats) + margin_deg,
        min(lons) - margin_deg,
        max(lons) + margin_deg,
    )


def _arrow_vec(cog: float, length: float) -> Tuple[float, float]:
    rad = math.radians(90 - cog)
    return length * math.cos(rad), -length * math.sin(rad)


def _text_wh(draw: ImageDraw.ImageDraw, txt: str, font) -> Tuple[int, int]:
    if hasattr(draw, "textbbox"):
        l, t, r, b = draw.textbbox((0, 0), txt, font=font)
        return r - l, b - t
    return draw.textsize(txt, font=font)


def _nonblank(img: Image.Image) -> bool:
    """True iff image has >1 colour (i.e. not flat white / black)."""
    extrema = img.convert("RGB").getextrema()  # [(min,max),...]
    return any(lo != hi for lo, hi in extrema)


def _whites_to_alpha(img: Image.Image, threshold: int = 250) -> Image.Image:
    """
    Make every (almost) white pixel fully transparent.

    Args
    ----
    img       : PIL.Image, any mode convertible to RGBA
    threshold : RGB value above which a channel is considered “white”

    Returns
    -------
    New PIL.Image in 'RGBA' mode.
    """
    rgba = img.convert("RGBA")
    arr = np.array(rgba, dtype=np.uint8)          # writable copy  H×W×4

    # mask of pixels where R, G, B are all > threshold → shape (H, W)
    white_mask = (arr[..., :3] > threshold).all(axis=-1)

    # set alpha channel (index 3) where mask is True
    arr[white_mask, 3] = 0
    arr[~white_mask, 3] = 255        # optional: force opaque elsewhere

    return Image.fromarray(arr, mode="RGBA")


# ------------------------------------------------------------------ #
# Main visualizer                                                    #
# ------------------------------------------------------------------ #
class VesselVisualizer:
    """Render vessels on a map and output PNG."""

    def __init__(self, vessels: Iterable[VesselData]) -> None:
        self.vessels: List[VesselData] = list(vessels)
        if not self.vessels:
            raise ValueError("empty vessel list")

    def render(self, outfile: str | Path, max_side: int = 800) -> Path:
        lats, lons = [v.lat for v in self.vessels], [v.lon for v in self.vessels]
        lat_min, lat_max, lon_min, lon_max = _bbox(lats, lons)
        lon_span, lat_span = lon_max - lon_min, (lat_max - lat_min) or 1e-6
        aspect = lon_span / lat_span
        w, h = (max_side, int(max_side / aspect)) if aspect >= 1 else (int(max_side * aspect), max_side)
        _LOG.info("PNG size %dx%d", w, h)

        forced = [
            (lon_min, lat_min),
            (lon_min, lat_max),
            (lon_max, lat_min),
            (lon_max, lat_max),
            *[(v.lon, v.lat) for v in self.vessels],
        ]

        # pick first non-blank background
        for tpl in BASE_TEMPLATES:
            base_img, zoom = self._render_layer(w, h, forced, tpl)
            if _nonblank(base_img):
                _LOG.debug("Background provider: %s", tpl)
                break
            _LOG.warning("Provider %s returned blank image", tpl)
        else:
            _LOG.error("All providers failed – white canvas")
            base_img = Image.new("RGB", (w, h), "white")
            zoom = 0

        # seamarks
        sea_img, _ = self._render_layer(w, h, forced, TEMPLATE_OPENSEA, zoom)

        if _nonblank(sea_img):
            if sea_img.size != base_img.size:
                sea_img = sea_img.resize(base_img.size, Image.BILINEAR)
            sea_img = _whites_to_alpha(sea_img)
            img = Image.alpha_composite(
                base_img.convert("RGBA"),
                sea_img,
            )
        else:
            img = base_img.convert("RGBA")

        self._draw_vessels(img, lon_min, lon_max, lat_min, lat_max)
        out = Path(outfile).with_suffix(".png")
        img.save(out, format="PNG", optimize=True)
        _LOG.info("Saved %s", out)
        return out

    # --------------- helpers ---------------------------------------- #
    @staticmethod
    def _render_layer(
        w: int, h: int, coords: list[Tuple[float, float]], template: str, zoom: int | None = None
    ) -> Tuple[Image.Image, int]:
        sm = StaticMap(w, h, url_template=template)
        for lon, lat in coords:
            sm.add_marker(CircleMarker((lon, lat), "#000000", 0))
        img = sm.render(zoom=zoom)
        zoom_out = getattr(sm, "zoom", getattr(sm, "_zoom", 0))
        return img, zoom_out

    def _draw_vessels(
        self, img: Image.Image, lon_min: float, lon_max: float, lat_min: float, lat_max: float
    ) -> None:
        w, h = img.size
        lon_span, lat_span = lon_max - lon_min, lat_max - lat_min
        draw = ImageDraw.Draw(img)
        font = ImageFont.load_default()
        arrow = max(10, int(0.05 * max(w, h)))

        def to_px(lon: float, lat: float) -> Tuple[float, float]:
            return ((lon - lon_min) / lon_span) * w, ((lat_max - lat) / lat_span) * h

        boxes: List[Tuple[float, float, float, float]] = []

        def free(b: Tuple[float, float, float, float]) -> bool:
            l, t, r, btm = b
            return all(r < l2 or r2 < l or btm < t2 or b2 < t for l2, t2, r2, b2 in boxes)

        for v in self.vessels:
            x, y = to_px(v.lon, v.lat)
            dx, dy = _arrow_vec(v.cog, arrow)
            #draw.line((x, y, x + dx, y + dy), width=2, fill="red")
            for ang in (150, -150):
                hx, hy = _arrow_vec(v.cog + ang, arrow * 0.4)
                draw.line((x + dx, y + dy, x + dx + hx, y + dy + hy), width=6, fill="red")

            label = f"{v.ship_name or v.mmsi}  {v.sog:.1f} kn"
            tw, th = _text_wh(draw, label, font)
            for ox, oy in ((10, -th - 10), (-tw - 10, -th - 10), (10, 10), (-tw - 10, 10)):
                box = (x + dx + ox - 2, y + dy + oy - 2, x + dx + ox + tw + 2, y + dy + oy + th + 2)
                if free(box):
                    boxes.append(box)
                    draw.rectangle(box, fill=(255, 255, 255, 200))
                    draw.text((box[0] + 2, box[1] + 2), label, fill="black", font=font)
                    break


# ------------------------------------------------------------------ #
# Demo                                                               #
# ------------------------------------------------------------------ #
def _demo() -> None:  # pragma: no cover
    clat, clon = 54.18, 7.88
    demo = [
        VesselData(
            mmsi=218032280 + i,
            lat=clat + (random() - 0.5) * 0.6,
            lon=clon + (random() - 0.5) * 0.6,
            sog=random() * 8,
            cog=randint(0, 359),
            ship_name=f"Demo{i}",
            call_sign=None,
            size=None,
            ais_class="B",
            timestamp=datetime.now(UTC),
        )
        for i in range(4)
    ]
    VesselVisualizer(demo).render("demo_map.png")


if __name__ == "__main__":  # pragma: no cover
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    argparse.ArgumentParser().parse_args()
    _demo()
