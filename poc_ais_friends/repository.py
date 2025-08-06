"""
repository.py ― data-access layer for AIS-friends bot
====================================================

A single *Repository* interface plus two interchangeable back-ends:

1. `InMemoryRepository`   – for unit tests & small demos
2. `SqliteRepository`     – durable storage in a lightweight file DB

Responsibilities
----------------
A. **Subscriptions** (Telegram user ⇄ MMSI)
B. **Last AIS snapshot** for every tracked MMSI
"""

from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, List, Mapping, MutableMapping, Protocol, Sequence, Set, Tuple

###############################################################################
# Domain objects
###############################################################################


@dataclass(slots=True, frozen=True)
class VesselData:
    """Immutable container for the most recent AIS fields we care about."""

    mmsi: int
    lat: float
    lon: float
    sog: float  # speed over ground, kn
    cog: float  # course over ground, °
    ship_name: str | None
    call_sign: str | None
    size: str | None           # "length x beam"  or any free text
    ais_class: str | None      # A, B, aids-to-navigation …
    timestamp: datetime        # UTC


###############################################################################
# Repository interface
###############################################################################


class Repository(Protocol):
    # --------------------------------------------------------------------- #
    # Subscription helpers                                                  #
    # --------------------------------------------------------------------- #

    def get_all_tracked_mmsi(self) -> List[int]:
        """Return **unique** MMSI codes currently tracked by any user."""

    def add_subscription(self, user_id: int, mmsi: int) -> bool:
        """Subscribe *user_id* to *mmsi* if they track fewer than 10 vessels.

        Returns:
            True  – subscription added
            False – user already subscribed **or** limit exceeded

        Example:
            >>> repo.add_subscription(123, 218032280)
            True
        """

    def remove_subscription(self, user_id: int, mmsi: int) -> bool:
        """Un-subscribe *user_id* from *mmsi*.

        Automatically removes *mmsi* from global list when no users remain.

        Returns:
            True  – subscription removed
            False – (user_id, mmsi) pair did not exist
        """

    def get_user_mmsi(self, user_id: int) -> List[int]:
        """List MMSI codes that *user_id* is currently tracking."""

    # --------------------------------------------------------------------- #
    # AIS snapshots                                                         #
    # --------------------------------------------------------------------- #

    def upsert_vessel(
        self,
        *,
        mmsi: int,
        lat: float,
        lon: float,
        sog: float,
        cog: float,
        ship_name: str | None,
        call_sign: str | None,
        size: str | None,
        ais_class: str | None,
        ts: datetime,
    ) -> None:
        """Insert or update the latest AIS snapshot for *mmsi*."""

    def get_vessel(self, mmsi: int) -> VesselData | None:
        """Return last known snapshot for *mmsi* or *None*."""

    # --------------------------------------------------------------------- #
    # House-keeping                                                         #
    # --------------------------------------------------------------------- #

    def purge_untracked_vessels(self) -> None:
        """Remove vessel rows that no longer have any subscribers."""


###############################################################################
# In-memory back-end
###############################################################################


class InMemoryRepository(Repository):
    """Volatile repository – best choice for tests."""

    # --------------------------- subscriptions --------------------------- #

    def __init__(self) -> None:
        self._user2mmsi: MutableMapping[int, Set[int]] = {}
        self._mmsi2user: MutableMapping[int, Set[int]] = {}
        self._vessels: MutableMapping[int, VesselData] = {}

    def get_all_tracked_mmsi(self) -> List[int]:
        return list(self._mmsi2user.keys())

    def add_subscription(self, user_id: int, mmsi: int) -> bool:
        subs = self._user2mmsi.setdefault(user_id, set())
        if mmsi in subs or len(subs) >= 10:
            return False
        subs.add(mmsi)
        self._mmsi2user.setdefault(mmsi, set()).add(user_id)
        return True

    def remove_subscription(self, user_id: int, mmsi: int) -> bool:
        subs = self._user2mmsi.get(user_id)
        if subs is None or mmsi not in subs:
            return False
        subs.remove(mmsi)
        if not subs:
            self._user2mmsi.pop(user_id, None)

        users = self._mmsi2user.get(mmsi, set())
        users.discard(user_id)
        if not users:
            self._mmsi2user.pop(mmsi, None)
            self._vessels.pop(mmsi, None)  # auto-purge snapshot
        return True

    def get_user_mmsi(self, user_id: int) -> List[int]:
        return sorted(self._user2mmsi.get(user_id, set()))

    # --------------------------- vessel data ---------------------------- #

    def upsert_vessel(
        self,
        *,
        mmsi: int,
        lat: float,
        lon: float,
        sog: float,
        cog: float,
        ship_name: str | None,
        call_sign: str | None,
        size: str | None,
        ais_class: str | None,
        ts: datetime,
    ) -> None:
        self._vessels[mmsi] = VesselData(
            mmsi=mmsi,
            lat=lat,
            lon=lon,
            sog=sog,
            cog=cog,
            ship_name=ship_name,
            call_sign=call_sign,
            size=size,
            ais_class=ais_class,
            timestamp=ts.astimezone(timezone.utc),
        )

    def get_vessel(self, mmsi: int) -> VesselData | None:
        return self._vessels.get(mmsi)

    # --------------------------- clean-up ------------------------------- #

    def purge_untracked_vessels(self) -> None:
        tracked = set(self._mmsi2user.keys())
        for mmsi in list(self._vessels.keys()):
            if mmsi not in tracked:
                self._vessels.pop(mmsi, None)


###############################################################################
# SQLite back-end
###############################################################################


class SqliteRepository(Repository):
    """Lightweight persistent storage – single writer / many readers."""

    _DDL = (
        # Subscriptions table
        """
        CREATE TABLE IF NOT EXISTS subscriptions (
            user_id INTEGER NOT NULL,
            mmsi     INTEGER NOT NULL,
            PRIMARY KEY (user_id, mmsi)
        );
        """,
        # Vessels table
        """
        CREATE TABLE IF NOT EXISTS vessels (
            mmsi       INTEGER PRIMARY KEY,
            lat        REAL NOT NULL,
            lon        REAL NOT NULL,
            sog        REAL NOT NULL,
            cog        REAL NOT NULL,
            ship_name  TEXT,
            call_sign  TEXT,
            size       TEXT,
            ais_class  TEXT,
            ts         TEXT NOT NULL
        );
        """,
    )

    # ---------------------------- helpers ------------------------------- #

    def __init__(self, db_path: str | Path = "ais_friends.db") -> None:
        self._conn = sqlite3.connect(
            str(db_path), detect_types=sqlite3.PARSE_DECLTYPES | sqlite3.PARSE_COLNAMES
        )
        self._conn.row_factory = sqlite3.Row
        self._bootstrap_schema()

    def _bootstrap_schema(self) -> None:
        for ddl in self._DDL:
            self._conn.execute(ddl)
        self._conn.commit()

    @contextmanager
    def _tx(self):
        """Context manager = implicit BEGIN/COMMIT/ROLLBACK."""
        try:
            yield
            self._conn.commit()
        except Exception:
            self._conn.rollback()
            raise

    # ------------------------- subscriptions --------------------------- #

    def get_all_tracked_mmsi(self) -> List[int]:
        cur = self._conn.execute("SELECT DISTINCT mmsi FROM subscriptions")
        return [row[0] for row in cur.fetchall()]

    def add_subscription(self, user_id: int, mmsi: int) -> bool:
        cnt = self._conn.execute(
            "SELECT COUNT(*) FROM subscriptions WHERE user_id = ?", (user_id,)
        ).fetchone()[0]
        if cnt >= 10:
            return False
        with self._tx():
            try:
                self._conn.execute(
                    "INSERT INTO subscriptions (user_id, mmsi) VALUES (?, ?)",
                    (user_id, mmsi),
                )
            except sqlite3.IntegrityError:
                return False
        return True

    def remove_subscription(self, user_id: int, mmsi: int) -> bool:
        with self._tx():
            cur = self._conn.execute(
                "DELETE FROM subscriptions WHERE user_id = ? AND mmsi = ?",
                (user_id, mmsi),
            )
            if cur.rowcount == 0:
                return False

            # delete vessel row when no subs left
            left = self._conn.execute(
                "SELECT 1 FROM subscriptions WHERE mmsi = ? LIMIT 1", (mmsi,)
            ).fetchone()
            if left is None:
                self._conn.execute("DELETE FROM vessels WHERE mmsi = ?", (mmsi,))
        return True

    def get_user_mmsi(self, user_id: int) -> List[int]:
        cur = self._conn.execute(
            "SELECT mmsi FROM subscriptions WHERE user_id = ? ORDER BY mmsi", (user_id,)
        )
        return [row[0] for row in cur.fetchall()]

    # --------------------------- vessel data --------------------------- #

    def upsert_vessel(
        self,
        *,
        mmsi: int,
        lat: float,
        lon: float,
        sog: float,
        cog: float,
        ship_name: str | None,
        call_sign: str | None,
        size: str | None,
        ais_class: str | None,
        ts: datetime,
    ) -> None:
        with self._tx():
            self._conn.execute(
                """
                INSERT INTO vessels
                  (mmsi, lat, lon, sog, cog, ship_name, call_sign, size, ais_class, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(mmsi) DO UPDATE SET
                  lat       = excluded.lat,
                  lon       = excluded.lon,
                  sog       = excluded.sog,
                  cog       = excluded.cog,
                  ship_name = excluded.ship_name,
                  call_sign = excluded.call_sign,
                  size      = excluded.size,
                  ais_class = excluded.ais_class,
                  ts        = excluded.ts
                """,
                (
                    mmsi,
                    lat,
                    lon,
                    sog,
                    cog,
                    ship_name,
                    call_sign,
                    size,
                    ais_class,
                    ts.astimezone(timezone.utc).isoformat(" "),
                ),
            )

    def get_vessel(self, mmsi: int) -> VesselData | None:
        row = self._conn.execute(
            "SELECT * FROM vessels WHERE mmsi = ?", (mmsi,)
        ).fetchone()
        if row is None:
            return None
        return VesselData(
            mmsi=row["mmsi"],
            lat=row["lat"],
            lon=row["lon"],
            sog=row["sog"],
            cog=row["cog"],
            ship_name=row["ship_name"],
            call_sign=row["call_sign"],
            size=row["size"],
            ais_class=row["ais_class"],
            timestamp=datetime.fromisoformat(row["ts"]).astimezone(timezone.utc),
        )

    # ------------------------------ GC --------------------------------- #

    def purge_untracked_vessels(self) -> None:
        with self._tx():
            self._conn.execute(
                """
                DELETE FROM vessels
                WHERE mmsi NOT IN (SELECT DISTINCT mmsi FROM subscriptions)
                """
            )


# ---------------------------------------------------------------------------
# Quick demonstration (run as script)
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    repo: Repository = InMemoryRepository()

    # Add sample subscriptions
    for m in (211234567, 987654321):
        repo.add_subscription(m, user_id=42)

    # Upsert vessel data
    repo.upsert_vessel(
        VesselData(
            mmsi=211234567,
            lat=54.321,
            lon=10.123,
            sog=5.2,
            cog=180.0,
            call_sign="DHAK",
            size=(9, 3),
            ais_class="B",
        )
    )

    print("Tracked MMSI:", repo.list_all_tracked_mmsi())
    print("User 42 tracks:", repo.list_user_mmsi(42))
    print("Vessel info:", repo.get_vessel(211234567))
