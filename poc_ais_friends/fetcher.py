"""
fetcher.py ― AIS‐stream client
==============================

High-level flow
---------------
1.  Build a *subscription* JSON (API key + MMSI list + global bounding box).
2.  Open a WebSocket to **wss://stream.aisstream.io/v0/stream**.
3.  Send the subscription once the socket is `OPEN`.
4.  Relay every *…PositionReport* to the injected *Repository*.
5.  Re-connect on network failure **or** when the repository’s MMSI set changes.
"""

from __future__ import annotations

import argparse
import asyncio as _aio
import json
import logging
import os
import ssl
from datetime import datetime, timezone
from typing import Iterable, List, Sequence

import certifi
import websockets

# --------------------------------------------------------------------------- #
# Logging                                                                     #
# --------------------------------------------------------------------------- #

_LOG = logging.getLogger("aisstream.fetcher")


# --------------------------------------------------------------------------- #
# Core class                                                                  #
# --------------------------------------------------------------------------- #

class AISStreamFetcher:
    """Maintain a live AIS feed and push fresh data into *repository*."""

    _WS_URL = "wss://stream.aisstream.io/v0/stream"

    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #
    # construction                                                          #
    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #

    def __init__(
        self,
        *,
        repository,
        api_key: str,
        refresh_sec: int = 3600,
        reconnect_sec: int = 60,
        verify_ssl: bool = True,
    ) -> None:
        """
        Args:
            repository: object that conforms to *Repository* interface
            api_key:    personal key from aisstream.io
            refresh_sec: reconnect & resend subscription every N seconds
            reconnect_sec: pause before re-dial on transport errors
            verify_ssl:  False ⇒ ignore bad TLS certificates
        """
        self._repo = repository
        self._api_key = api_key
        self._refresh = refresh_sec
        self._retry = reconnect_sec
        self._ssl = _make_ssl_ctx(verify_ssl)
        self._last_mmsi: Sequence[int] | None = None

    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #
    # public API                                                             #
    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #

    async def run_forever(self) -> None:
        """
        Main coroutine; never returns under normal operation.

        Example:
            >>> fetcher = AISStreamFetcher(repository=my_repo, api_key="abc")
            >>> _aio.run(fetcher.run_forever())
        """
        _LOG.info(
            "AISStreamFetcher started (refresh=%ss reconnect=%ss ssl_verify=%s)",
            self._refresh,
            self._retry,
            bool(self._ssl),
        )
        while True:
            await self._stream_loop()
            _LOG.info("Sleeping %ss before reconnect …", self._retry)
            await _aio.sleep(self._retry)

    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #
    # private helpers – network loop                                        #
    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #

    async def _stream_loop(self) -> None:
        """Connect, subscribe and stay until error or refresh timeout."""
        mmsi = self._repo.get_all_tracked_mmsi()
        if not mmsi:
            _LOG.info("No MMSI to follow – sleep %s s", self._retry)
            await _aio.sleep(self._retry)
            return

        if mmsi == self._last_mmsi:
            _LOG.debug("Tracked MMSI unchanged (%d) – reuse connection", len(mmsi))
        else:
            _LOG.info("Tracked MMSI updated: %s", mmsi)
        self._last_mmsi = list(mmsi)

        try:
            async with websockets.connect(self._WS_URL, ssl=self._ssl) as ws:
                await ws.send(self._build_subscribe_msg(mmsi))
                _LOG.debug("Subscribe payload sent")
                listener = _aio.create_task(self._listen(ws))
                await _aio.wait(
                    {listener},
                    timeout=self._refresh,
                    return_when=_aio.FIRST_EXCEPTION,
                )
                listener.cancel()
        except Exception as exc:
            _LOG.error("WebSocket error: %s; retry in %ss", exc, self._retry)
            return

    async def _listen(self, ws) -> None:
        """Receive messages, decode JSON (text or binary) and process."""
        async for raw in ws:
            try:
                data = json.loads(raw if isinstance(raw, str) else raw.decode())
            except json.JSONDecodeError:
                _LOG.warning("Skip non-JSON frame")
                continue
            msg_type = data.get("MessageType")
            if not (msg_type and msg_type.endswith("PositionReport")):
                _LOG.debug("Skip message type %s", msg_type)
                continue
            pos = _extract_position(data)
            if pos is None:
                _LOG.warning("Invalid PositionReport: %s", data)
                continue
            self._repo.upsert_vessel(**pos)
            _LOG.debug(
                "Stored PositionReport for MMSI %s (lat %.5f lon %.5f)",
                pos["mmsi"],
                pos["lat"],
                pos["lon"],
            )

    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #
    # private helpers – payload / parsing                                    #
    # ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ #

    def _build_subscribe_msg(self, mmsi: Sequence[int]) -> str:
        """Return JSON string understood by aisstream server."""
        payload = {
            "Apikey": self._api_key,
            "BoundingBoxes": [[[-90, -180], [90, 180]]],
            "FiltersShipMMSI": [str(x) for x in mmsi],
            "FiltersMessageTypes": ["positionreport"],
        }
        _LOG.debug("Subscribe payload: %s", payload)
        return json.dumps(payload)


# --------------------------------------------------------------------------- #
# Utility functions                                                           #
# --------------------------------------------------------------------------- #

def _make_ssl_ctx(verify: bool) -> ssl.SSLContext | None:
    """Return a SSL context that trusts *certifi* bundle or `None` (no TLS)."""
    if not verify:
        _LOG.warning("TLS verification disabled – DO NOT USE IN PRODUCTION!")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    ctx = ssl.create_default_context(cafile=certifi.where())
    return ctx


def _extract_position(data: dict) -> dict | None:
    """
    Pull out lat/lon/sog/cog + meta that we need for repository.upsert_vessel().

    Returns **kwargs** ready for upsert or *None* when mandatory fields missing.
    """
    try:
        meta = data["MetaData"]
        body = next(iter(data["Message"].values()))

        # robust timestamp parsing ― keep date+time, drop TZ garbage
        date_part, time_part, *_ = meta["time_utc"].split()
        ts = datetime.fromisoformat(f"{date_part}T{time_part}").replace(
            tzinfo=timezone.utc
        )

        return {
            "mmsi": int(meta["MMSI"]),
            "lat": float(meta["latitude"]),
            "lon": float(meta["longitude"]),
            "sog": float(body.get("Sog", 0)),
            "cog": float(body.get("Cog", 0)),
            "ship_name": meta.get("ShipName"),
            "call_sign": body.get("CallSign"),
            "size": None,
            "ais_class": data["MessageType"],
            "ts": ts,
        }
    except (KeyError, ValueError, TypeError):
        return None


# --------------------------------------------------------------------------- #
# Quick demonstration                                                         #
# --------------------------------------------------------------------------- #

if __name__ == "__main__":  # pragma: no cover
    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    from repository import InMemoryRepository  # local import

    async def _demo() -> None:
        repo = InMemoryRepository()
        repo.add_subscription(user_id=1, mmsi=218032280)  # ← Rassvet
        fetcher = AISStreamFetcher(
            repository=repo,
            api_key=os.getenv("AISSTREAM_API_KEY", "<missing>"),
            refresh_sec=600,
            reconnect_sec=60,
            verify_ssl=not _ARGS.no_verify,
        )
        await fetcher.run_forever()

    _P = argparse.ArgumentParser(description="Run AIS fetcher demo")
    _P.add_argument("--no-verify", action="store_true", help="disable TLS check")
    _ARGS = _P.parse_args()
    _aio.run(_demo())
