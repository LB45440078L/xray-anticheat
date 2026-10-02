#!/usr/bin/env python3
"""XRay AntiCheat -- interactive 3D mining visualiser.

This is a moderator tool. It reads the evidence store written by the XRay
anti-cheat plugin (see ``xray-persistence/.../V1__initial_schema.sql``) and
renders, for one or more players, a single rotatable 3D scene:

  * the player trajectory (polyline) with direction arrows,
  * the mined-block trail (the tunnel actually excavated),
  * every ore discovery, coloured by ore type and shaped/filled by exposure
    (hidden vs exposed) using a colour-blind-safe palette,
  * "targeting vectors": for discoveries whose ``move_alignment_deg`` is small
    (i.e. the player was already travelling almost directly at the ore long
    before it could have been seen), a line from the approach point to the ore.

The scene can be shown interactively (rotate/zoom with the mouse) or written
to a PNG headlessly via ``--output``.

Coordinate convention
---------------------
Minecraft uses a right-handed system with **Y as the vertical axis**. Matplotlib
renders a 3D Axes with its own Z as "up", so every point is mapped as
``world(X, Y, Z) -> plot(X, Z, Y)``. The axis labels are set to make this
explicit, and the box aspect is equalised so block distances are not distorted.

Data sources
------------
SQLite is supported unconditionally (stdlib ``sqlite3``). PostgreSQL and
MariaDB/MySQL are supported on a best-effort basis. When SQLAlchemy is
importable it is preferred for every backend; otherwise the tool falls back to
per-driver DB-API modules (``psycopg``/``psycopg2`` for PostgreSQL,
``pymysql``/``mariadb`` for MariaDB/MySQL).

Optional dependencies
---------------------
``scipy`` is **not** imported at module load and is never required. SQLAlchemy
is likewise optional (it is only imported lazily when a non-SQLite URL, or the
demo's in-memory store, needs it). ``numpy``, ``pandas`` and ``matplotlib`` are
required.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import os
import re
import sys
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# Domain constants
# ---------------------------------------------------------------------------

#: The only ``discovery_exposure`` value that counts as "hidden". Per the
#: persistence layer, everything else (FULLY_EXPOSED, PARTIALLY_EXPOSED,
#: CONDITIONALLY_EXPOSED, UNKNOWN) is treated as exposed. Keeping the literal in
#: one place makes that rule auditable.
HIDDEN_EXPOSURE = "HIDDEN"

#: Marker shapes: exposed ores are circles, hidden ores are triangles. Shape is
#: the primary cue because it survives greyscale printing and colour blindness.
EXPOSED_MARKER = "o"
HIDDEN_MARKER = "^"

#: Colour-blind-safe palette (Okabe-Ito) mapped onto ore types. Colours are
#: re-used across ore types only when the palette runs out; known ores always
#: get a stable colour so a moderator can build muscle memory.
ORE_COLORS: dict[str, str] = {
    "diamond": "#56B4E9",       # sky blue
    "emerald": "#009E73",       # bluish green
    "ancient_debris": "#D55E00",  # vermillion
    "netherite": "#8C510A",     # brown (netherite scrap / debris family)
    "gold": "#E69F00",          # orange
    "iron": "#999999",          # grey
    "copper": "#CC79A7",        # reddish purple
    "redstone": "#B03A2E",      # brick red
    "lapis": "#0072B2",         # blue
    "quartz": "#F0E442",        # yellow
    "coal": "#5A5A5A",          # dark grey
    "amethyst": "#A56CC1",      # violet
}

#: Fallback colours used cyclically for ore types not present in ORE_COLORS.
_FALLBACK_COLORS: tuple[str, ...] = (
    "#56B4E9", "#E69F00", "#009E73", "#CC79A7", "#0072B2", "#D55E00", "#F0E442",
)

#: Distinct line colours for per-player trajectories when several players are
#: shown at once.
_TRAJECTORY_COLORS: tuple[str, ...] = ("#5DA9E9", "#F2B134", "#7ED957", "#E86A92")

UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

#: Colour used for the suspicious targeting vectors.
_TARGETING_COLOR = "#FF5C5C"

# Columns actually required from each table. Selecting explicitly keeps the tool
# working even if the schema grows extra columns, and documents the dependency.
_DISCOVERY_COLUMNS = (
    "id, player_id, session_id, world_key, ore_id, x, y, z, discovery_exposure, "
    "vein_size, hidden_vein_blocks, exposed_vein_blocks, blocks_since_previous, "
    "distance_since_previous, move_alignment_deg, look_alignment_deg, "
    "evidence_discount, occurred_at"
)
_MINING_COLUMNS = (
    "id, player_id, session_id, world_key, x, y, z, block_key, origin, tick, "
    "occurred_at"
)


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

def parse_timestamp(value: str | None) -> int | None:
    """Parse a ``--since``/``--until`` value into epoch milliseconds.

    Accepts a bare integer (already epoch millis, which is how the schema stores
    time) or an ISO-8601 string. Naive ISO timestamps are interpreted as UTC,
    matching the persistence layer's convention of storing UTC epoch millis.
    """
    if value is None:
        return None
    text = value.strip()
    if not text:
        return None
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    iso = text[:-1] + "+00:00" if text.endswith(("Z", "z")) else text
    try:
        parsed = _dt.datetime.fromisoformat(iso)
    except ValueError as exc:  # pragma: no cover - argument error path
        raise argparse.ArgumentTypeError(
            f"not an epoch-millis integer or ISO-8601 timestamp: {value!r}"
        ) from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=_dt.timezone.utc)
    return int(parsed.timestamp() * 1000)


def format_millis(millis: int | None) -> str:
    """Render epoch millis as a readable UTC timestamp for titles/labels."""
    if millis is None:
        return "-"
    stamp = _dt.datetime.fromtimestamp(millis / 1000.0, tz=_dt.timezone.utc)
    return stamp.strftime("%Y-%m-%d %H:%M:%SZ")


def _hex_to_rgb(hex_color: str) -> tuple[float, float, float]:
    h = hex_color.lstrip("#")
    return (int(h[0:2], 16) / 255.0, int(h[2:4], 16) / 255.0, int(h[4:6], 16) / 255.0)


def shade(hex_color: str, factor: float) -> tuple[float, float, float]:
    """Return a darkened copy of ``hex_color`` (factor < 1 darkens).

    Hidden ores are drawn with a darkened fill of their ore colour so that
    "hidden vs exposed" is distinguishable by colour *and* marker shape, while
    the hue still identifies the ore type.
    """
    r, g, b = _hex_to_rgb(hex_color)
    return (min(r * factor, 1.0), min(g * factor, 1.0), min(b * factor, 1.0))


def ore_color(ore_id: str, index: int = 0) -> str:
    """Stable colour-blind-safe colour for an ore type."""
    return ORE_COLORS.get(ore_id, _FALLBACK_COLORS[index % len(_FALLBACK_COLORS)])


# ---------------------------------------------------------------------------
# Database access
# ---------------------------------------------------------------------------

@dataclass
class Database:
    """A thin read-only wrapper over either a SQLAlchemy engine or a DB-API conn.

    SQL queries are written once with SQLAlchemy named parameters (``:name``).
    The DB-API fallback rewrites those placeholders to the driver's paramstyle,
    which keeps a single set of query builders for every backend.
    """

    dialect: str
    label: str
    _engine: Any = None       # sqlalchemy Engine, when available
    _conn: Any = None         # DB-API connection, otherwise
    _paramstyle: str = "named"

    def read(self, sql: str, params: Mapping[str, Any] | None = None) -> pd.DataFrame:
        """Execute a SELECT and return the rows as a DataFrame."""
        params = dict(params or {})
        if self._engine is not None:
            from sqlalchemy import text  # imported lazily; SQLAlchemy is optional
            return pd.read_sql(text(sql), self._engine, params=params)
        adapted, sequence = _adapt_params(sql, params, self._paramstyle)
        cursor = self._conn.cursor()
        try:
            cursor.execute(adapted, sequence)
            columns = [d[0] for d in cursor.description]
            rows = cursor.fetchall()
        finally:
            cursor.close()
        return pd.DataFrame(rows, columns=columns)


def _adapt_params(
    sql: str, params: Mapping[str, Any], paramstyle: str
) -> tuple[str, Sequence[Any]]:
    """Rewrite ``:name`` placeholders for a DB-API paramstyle.

    Only the substitution style changes; the ordering follows first appearance
    so qmark/numeric drivers receive a correctly ordered sequence.
    """
    names = re.findall(r":(\w+)", sql)
    if paramstyle == "named":
        return sql, params
    if paramstyle == "qmark":
        return re.sub(r":(\w+)", "?", sql), [params[n] for n in names]
    if paramstyle == "format":
        return re.sub(r":(\w+)", "%s", sql), [params[n] for n in names]
    if paramstyle == "pyformat":
        return re.sub(r":(\w+)", "%(" + r"\1" + ")s", sql), params
    return sql, [params[n] for n in names]


def normalize_database_url(raw: str) -> str:
    """Normalise a user-supplied database URL.

    * a bare filesystem path (or ``sqlite:`` shorthand) becomes a SQLite URL;
    * ``postgres://`` is rewritten to the SQLAlchemy spelling;
    * ``mariadb://`` is routed to the MySQL dialect, since SQLAlchemy has no
      separate MariaDB scheme.
    """
    text = raw.strip()
    if "://" not in text:
        # Treat as a path to a SQLite database file.
        path = os.path.abspath(os.path.expanduser(text))
        return f"sqlite:///{path}"
    if text.startswith("sqlite:"):
        return text
    if text.startswith("postgres://"):
        return "postgresql://" + text[len("postgres://"):]
    if text.startswith("mariadb://"):
        return "mysql://" + text[len("mariadb://"):]
    return text


def open_database(raw_url: str) -> Database:
    """Open a :class:`Database` for the given URL.

    SQLAlchemy is used when importable (best cross-backend support). Otherwise
    the tool degrades to per-driver DB-API modules; SQLite always works.
    """
    url = normalize_database_url(raw_url)
    try:
        from sqlalchemy import create_engine
        from sqlalchemy.engine import make_url
    except ImportError:
        return _open_database_dbapi(url)

    engine = create_engine(url)
    return Database(dialect=engine.dialect.name, label=str(make_url(url).render_as_string(hide_password=True)), _engine=engine)


def _open_database_dbapi(url: str) -> Database:  # pragma: no cover - fallback path
    """Best-effort DB-API fallback when SQLAlchemy is unavailable."""
    if url.startswith("sqlite:"):
        import sqlite3

        remainder = url[len("sqlite:"):]
        target = remainder[len("///"):] if remainder.startswith("///") else remainder.lstrip("/")
        if target in ("", ":memory:"):
            conn = sqlite3.connect(":memory:")
            label = "sqlite:///:memory:"
        else:
            conn = sqlite3.connect(target)
            label = f"sqlite:///{target}"
        return Database("sqlite", label, _conn=conn, _paramstyle="qmark")

    if url.startswith("postgresql") or url.startswith("postgres"):
        for module_name in ("psycopg", "psycopg2"):
            try:
                module = __import__(module_name)
            except ImportError:
                continue
            rest = url.split("://", 1)[1]
            # Strip a query string; the DB-API modules do not accept it here.
            rest = rest.split("?", 1)[0]
            userinfo, hostpart = rest.rsplit("@", 1) if "@" in rest else ("", rest)
            hostport, _, dbname = hostpart.partition("/")
            host, _, port = hostport.partition(":")
            user, _, password = userinfo.partition(":")
            conn = module.connect(
                dbname=dbname, user=user or None, password=password or None,
                host=host or None, port=int(port) if port else None,
            )
            return Database("postgresql", module_name, _conn=conn, _paramstyle="pyformat")

    if url.startswith(("mysql", "mariadb")):
        for module_name in ("pymysql", "mariadb"):
            try:
                module = __import__(module_name)
            except ImportError:
                continue
            rest = url.split("://", 1)[1].split("?", 1)[0]
            userinfo, hostpart = rest.rsplit("@", 1) if "@" in rest else ("", rest)
            hostport, _, dbname = hostpart.partition("/")
            host, _, port = hostport.partition(":")
            user, _, password = userinfo.partition(":")
            conn = module.connect(
                db=dbname, user=user or None, password=password or None,
                host=host or None, port=int(port) if port else 3306,
            )
            return Database("mysql", module_name, _conn=conn, _paramstyle="pyformat")

    raise SystemExit(
        f"Unsupported database URL or missing driver: {url!r}. Install SQLAlchemy "
        f"or the appropriate DB-API driver."
    )


# ---------------------------------------------------------------------------
# Query layer
# ---------------------------------------------------------------------------

def resolve_player(database: Database, player: str) -> str:
    """Resolve a UUID *or* a player name to a concrete player id.

    A UUID is taken verbatim (no lookup) because the id is the identity. A name
    is matched case-insensitively; the most recently seen account wins when a
    name has been reused.
    """
    candidate = player.strip()
    if UUID_RE.match(candidate):
        return candidate
    query = (
        "SELECT id, name, last_seen FROM players "
        "WHERE LOWER(name) = LOWER(:name) "
        "ORDER BY last_seen DESC"
    )
    rows = database.read(query, {"name": candidate})
    if rows.empty:
        raise SystemExit(f"No player with name or UUID {player!r} in this database.")
    return str(rows.iloc[0]["id"])


def list_players(database: Database) -> pd.DataFrame:
    """All known players, ordered by most-recently-seen."""
    return database.read(
        "SELECT id, name, first_seen, last_seen FROM players ORDER BY last_seen DESC"
    )


def _in_clause(column: str, values: Sequence[str], params: dict[str, Any], prefix: str) -> str:
    """Add ``column IN (:prefix0, :prefix1, ...)`` guarding against injection."""
    keys = []
    for i, value in enumerate(values):
        key = f"{prefix}{i}"
        params[key] = value
        keys.append(f":{key}")
    return f"{column} IN ({', '.join(keys)})"


def load_discoveries(
    database: Database,
    player_id: str,
    *,
    world: str | None = None,
    ores: Sequence[str] = (),
    since: int | None = None,
    until: int | None = None,
    exposure: str = "both",
    min_confidence: float | None = None,
) -> pd.DataFrame:
    """Load ore discoveries for one player after applying every CLI filter."""
    params: dict[str, Any] = {"pid": player_id}
    sql = [
        f"SELECT {_DISCOVERY_COLUMNS} FROM ore_discoveries WHERE player_id = :pid"
    ]
    if world:
        sql.append("AND world_key = :world")
        params["world"] = world
    if ores:
        sql.append("AND " + _in_clause("ore_id", ores, params, "ore"))
    if since is not None:
        sql.append("AND occurred_at >= :since")
        params["since"] = int(since)
    if until is not None:
        sql.append("AND occurred_at <= :until")
        params["until"] = int(until)
    if exposure == "hidden":
        sql.append("AND discovery_exposure = :hidden")
        params["hidden"] = HIDDEN_EXPOSURE
    elif exposure == "exposed":
        # Everything that is not literally HIDDEN counts as exposed.
        sql.append("AND discovery_exposure <> :hidden")
        params["hidden"] = HIDDEN_EXPOSURE
    if min_confidence is not None:
        # evidence_discount is the per-discovery trust multiplier in [0, 1] that
        # the analyser assigns; a low value means the evidence is weak. It is
        # the closest per-row analogue of "confidence".
        sql.append("AND evidence_discount >= :minconf")
        params["minconf"] = float(min_confidence)
    sql.append("ORDER BY occurred_at ASC")
    return database.read(" ".join(sql), params)


def load_mining_events(
    database: Database,
    player_id: str,
    *,
    world: str | None = None,
    since: int | None = None,
    until: int | None = None,
) -> pd.DataFrame:
    """Load the mined-block trail for one player (used for trajectory/tunnel)."""
    params: dict[str, Any] = {"pid": player_id}
    sql = [f"SELECT {_MINING_COLUMNS} FROM mining_events WHERE player_id = :pid"]
    if world:
        sql.append("AND world_key = :world")
        params["world"] = world
    if since is not None:
        sql.append("AND occurred_at >= :since")
        params["since"] = int(since)
    if until is not None:
        sql.append("AND occurred_at <= :until")
        params["until"] = int(until)
    sql.append("ORDER BY occurred_at ASC")
    return database.read(" ".join(sql), params)


# ---------------------------------------------------------------------------
# Demo dataset (in-memory SQLite, no server required)
# ---------------------------------------------------------------------------

_DEMO_DDL = """
CREATE TABLE players (
    id         VARCHAR(36) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    first_seen BIGINT NOT NULL,
    last_seen  BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE mining_events (
    id          VARCHAR(36)  NOT NULL,
    player_id   VARCHAR(36)  NOT NULL,
    session_id  VARCHAR(36),
    world_key   VARCHAR(128) NOT NULL,
    x           BIGINT       NOT NULL,
    y           BIGINT       NOT NULL,
    z           BIGINT       NOT NULL,
    block_key   VARCHAR(128) NOT NULL,
    origin      VARCHAR(32)  NOT NULL,
    tick        BIGINT       NOT NULL,
    occurred_at BIGINT       NOT NULL,
    PRIMARY KEY (id)
);
CREATE TABLE ore_discoveries (
    id                      VARCHAR(36) NOT NULL,
    player_id               VARCHAR(36) NOT NULL,
    session_id              VARCHAR(36),
    world_key               VARCHAR(128) NOT NULL,
    ore_id                  VARCHAR(64) NOT NULL,
    x                       BIGINT NOT NULL,
    y                       BIGINT NOT NULL,
    z                       BIGINT NOT NULL,
    discovery_exposure      VARCHAR(32) NOT NULL,
    vein_size               INTEGER NOT NULL,
    hidden_vein_blocks      INTEGER NOT NULL,
    exposed_vein_blocks     INTEGER NOT NULL,
    blocks_since_previous   DOUBLE PRECISION NOT NULL,
    distance_since_previous DOUBLE PRECISION NOT NULL,
    move_alignment_deg      DOUBLE PRECISION,
    look_alignment_deg      DOUBLE PRECISION,
    evidence_discount       DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    occurred_at             BIGINT NOT NULL,
    PRIMARY KEY (id)
);
"""


def _segment(start: np.ndarray, end: np.ndarray, step: float = 1.0) -> list[tuple[int, int, int]]:
    """Integer block positions along the straight line ``start -> end``."""
    delta = end - start
    distance = float(np.linalg.norm(delta))
    count = max(2, int(distance / step) + 1)
    points = []
    for k in range(count):
        p = start + delta * (k / (count - 1))
        points.append((int(round(p[0])), int(round(p[1])), int(round(p[2]))))
    return points


def build_demo_database(seed: int = 1337) -> Database:
    """Create an in-memory SQLite store with two contrasting, verifiable players.

    * ``CaveExplorer`` wanders a cave, encounters mostly *exposed* ores with
      large approach angles -- the legitimate baseline.
    * ``OreTargeter`` tunnels in near-straight lines straight at *hidden* ores
      with small ``move_alignment_deg`` -- the suspicious pattern the plugin is
      built to surface.

    The store is genuine SQLite (:memory:) accessed through SQLAlchemy's
    StaticPool so every connection sees the same in-memory database. Nothing
    here touches a real server, which is what makes the tool verifiable offline.
    """
    try:
        from sqlalchemy import create_engine
        from sqlalchemy.pool import StaticPool
    except ImportError as exc:  # pragma: no cover - fallback path
        raise SystemExit(
            "Demo mode builds an in-memory SQLite store via SQLAlchemy; "
            "install it (pip install sqlalchemy) or pass --database-url."
        ) from exc

    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        # sqlite3 refuses multiple statements per execute(), so split the DDL.
        for statement in _DEMO_DDL.split(";"):
            if statement.strip():
                conn.exec_driver_sql(statement)

    rng = np.random.default_rng(seed)
    world = "world"
    base_time = int(_dt.datetime(2026, 9, 20, 10, 0, tzinfo=_dt.timezone.utc).timestamp() * 1000)

    players: list[dict[str, Any]] = []
    mining: list[dict[str, Any]] = []
    discoveries: list[dict[str, Any]] = []

    # ----- Player 1: the legitimate cave explorer -------------------------
    explorer_id = "11111111-1111-1111-1111-111111111111"
    explorer_name = "CaveExplorer"
    steps = np.arange(140)
    ex = (50 + 1.2 * steps + 6 * np.sin(steps / 9.0)).round().astype(int)
    ey = (34 + 5 * np.sin(steps / 13.0) + 2 * np.cos(steps / 6.0)).round().astype(int)
    ez = (60 + 0.9 * steps + 5 * np.cos(steps / 11.0)).round().astype(int)

    def add_mining(player_id: str, seq: int, x: int, y: int, z: int, when: int) -> None:
        mining.append({
            "id": f"me-{player_id[:4]}-{seq:05d}", "player_id": player_id,
            "session_id": f"session-{player_id[:4]}", "world_key": world,
            "x": int(x), "y": int(y), "z": int(z),
            "block_key": "deepslate" if y < 16 else "stone",
            "origin": "PLAYER", "tick": seq * 20, "occurred_at": when,
        })

    for i in range(len(steps)):
        add_mining(explorer_id, i, ex[i], ey[i], ez[i], base_time + i * 20_000)

    # Explorer discovers exposed ores with large approach angles.
    explorer_ores = ["coal", "iron", "redstone", "gold", "lapis", "diamond"]
    explorer_weights = np.array([0.34, 0.28, 0.16, 0.10, 0.07, 0.05])
    explorer_weights = explorer_weights / explorer_weights.sum()
    for i, idx in enumerate(range(8, len(steps), 12)):
        ore = str(rng.choice(explorer_ores, p=explorer_weights))
        exposure = str(rng.choice(
            ["FULLY_EXPOSED", "PARTIALLY_EXPOSED", "CONDITIONALLY_EXPOSED"],
            p=[0.6, 0.3, 0.1],
        ))
        vein = int(rng.integers(2, 9))
        move_deg = float(rng.uniform(45.0, 175.0))  # genuinely meandering approach
        look_deg = float(rng.uniform(20.0, 90.0))
        discoveries.append({
            "id": f"od-explorer-{i:03d}", "player_id": explorer_id,
            "session_id": f"session-{explorer_id[:4]}", "world_key": world,
            "ore_id": ore, "x": int(ex[idx]), "y": int(ey[idx]), "z": int(ez[idx]),
            "discovery_exposure": exposure, "vein_size": vein,
            "hidden_vein_blocks": 0 if exposure != "PARTIALLY_EXPOSED" else max(1, vein // 3),
            "exposed_vein_blocks": vein,
            "blocks_since_previous": float(rng.uniform(5, 40)),
            "distance_since_previous": float(rng.uniform(5, 40)),
            "move_alignment_deg": move_deg, "look_alignment_deg": look_deg,
            "evidence_discount": float(rng.uniform(0.75, 1.0)),
            "occurred_at": base_time + int(idx) * 20_000,
        })

    players.append({
        "id": explorer_id, "name": explorer_name,
        "first_seen": base_time, "last_seen": base_time + len(steps) * 20_000,
    })

    # ----- Player 2: the suspicious ore targeter --------------------------
    targeter_id = "22222222-2222-2222-2222-222222222222"
    targeter_name = "OreTargeter"
    t_start = base_time + 6 * 60 * 60 * 1000  # six hours later

    # Hidden, deep targets scattered far from spawn.
    n_targets = 11
    targets = []
    for _ in range(n_targets):
        tx = int(rng.integers(320, 470))
        tz = int(rng.integers(320, 470))
        ty = int(rng.integers(-50, 18))  # deepslate layer: hidden diamond territory
        targets.append(np.array([tx, ty, tz], dtype=float))

    current = np.array([460.0, 45.0, 460.0])
    seq = 0
    clock = t_start
    target_ores = ["diamond", "diamond", "emerald", "ancient_debris", "gold", "redstone"]

    for i, target in enumerate(targets):
        # L-shaped tunnel: first drop/rise to the target's Y, then drive straight
        # in on the X/Z plane. This is the geometry an X-rayer's path exhibits.
        corner = np.array([current[0], target[1], current[2]], dtype=float)
        for leg in (_segment(current, corner, step=2.0), _segment(corner, target, step=1.0)):
            for (bx, by, bz) in leg:
                add_mining(targeter_id, seq, bx, by, bz, clock)
                seq += 1
                clock += 4_000
        current = target

        ore = str(rng.choice(target_ores))
        vein = int(rng.integers(3, 9))
        # Small move alignment = the player was already heading at the ore; and
        # occasionally NULL, which must not be coerced to zero.
        move_deg = None if rng.random() < 0.12 else float(rng.uniform(0.0, 8.0))
        discoveries.append({
            "id": f"od-targeter-{i:03d}", "player_id": targeter_id,
            "session_id": f"session-{targeter_id[:4]}", "world_key": world,
            "ore_id": ore, "x": int(target[0]), "y": int(target[1]), "z": int(target[2]),
            "discovery_exposure": "HIDDEN" if rng.random() < 0.85 else "PARTIALLY_EXPOSED",
            "vein_size": vein, "hidden_vein_blocks": vein, "exposed_vein_blocks": 0,
            "blocks_since_previous": float(rng.uniform(60, 220)),
            "distance_since_previous": float(rng.uniform(60, 220)),
            "move_alignment_deg": move_deg,
            "look_alignment_deg": None if move_deg is None else float(rng.uniform(0.0, 6.0)),
            "evidence_discount": float(rng.uniform(0.85, 1.0)),
            "occurred_at": clock,
        })

    players.append({
        "id": targeter_id, "name": targeter_name,
        "first_seen": t_start, "last_seen": clock,
    })

    pd.DataFrame(players).to_sql("players", engine, if_exists="append", index=False)
    pd.DataFrame(mining).to_sql("mining_events", engine, if_exists="append", index=False)
    pd.DataFrame(discoveries).to_sql("ore_discoveries", engine, if_exists="append", index=False)

    return Database(dialect="sqlite", label="demo://in-memory-sqlite", _engine=engine)


# ---------------------------------------------------------------------------
# Plotting
# ---------------------------------------------------------------------------

def _setup_matplotlib(no_interactive: bool):
    """Configure the matplotlib backend and return ``pyplot``.

    pyplot must be imported *after* ``matplotlib.use`` so the backend choice
    takes effect, hence this helper rather than a module-level import.
    """
    import matplotlib

    headless = no_interactive or (
        sys.platform.startswith("linux")
        and not os.environ.get("DISPLAY")
        and not os.environ.get("WAYLAND_DISPLAY")
    )
    if headless:
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    try:
        plt.style.use("dark_background")
    except Exception:  # pragma: no cover - style is cosmetic only
        pass
    return plt


def _to_plot(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Map ``world(X, Y, Z) -> plot(X, Z, Y)`` so Minecraft Y points up."""
    return (
        df["x"].to_numpy(dtype=float),
        df["z"].to_numpy(dtype=float),
        df["y"].to_numpy(dtype=float),
    )


def _equalise_axes(ax, points: np.ndarray) -> None:
    """Give the 3D box equal aspect so block distances are not distorted."""
    if points.size == 0:
        return
    mins = points.min(axis=0)
    maxs = points.max(axis=0)
    spans = np.maximum(maxs - mins, 1.0)
    centre = (mins + maxs) / 2.0
    radius = float(spans.max()) / 2.0
    ax.set_xlim(centre[0] - radius, centre[0] + radius)
    ax.set_ylim(centre[1] - radius, centre[1] + radius)
    ax.set_zlim(centre[2] - radius, centre[2] + radius)
    try:
        ax.set_box_aspect((1, 1, 1))
    except AttributeError:  # pragma: no cover - very old matplotlib
        pass


def render_scene(
    plt,
    *,
    players: Sequence[tuple[str, str]],
    mining_by_player: Mapping[str, pd.DataFrame],
    discoveries: pd.DataFrame,
    title: str,
    max_align_deg: float,
    output: str | None,
    show: bool,
) -> None:
    """Build the whole 3D scene. Pure rendering -- no database access."""
    from matplotlib.lines import Line2D

    fig = plt.figure(figsize=(15, 11))
    ax = fig.add_subplot(111, projection="3d")
    ax.set_facecolor("#101418")
    fig.patch.set_facecolor("#0b0e12")
    for axis in (ax.xaxis, ax.yaxis, ax.zaxis):
        axis.set_pane_color((0.06, 0.08, 0.10, 1.0))
        axis.label.set_color("#dddddd")
    ax.tick_params(colors="#999999", labelsize=8)
    ax.grid(True, color="#2a323a", linewidth=0.5)

    legend_handles: list[Any] = []
    all_points: list[np.ndarray] = []

    # ----- trajectories + mined-block trails ------------------------------
    for index, (player_id, player_name) in enumerate(players):
        mined = mining_by_player.get(player_id)
        if mined is None or mined.empty:
            continue
        mined = mined.sort_values("occurred_at", kind="stable").reset_index(drop=True)
        px, py, pz = _to_plot(mined)
        all_points.append(np.column_stack([px, py, pz]))
        line_color = _TRAJECTORY_COLORS[index % len(_TRAJECTORY_COLORS)]

        # The tunnel: the actual excavated blocks, drawn small and desaturated.
        ax.scatter(px, py, pz, s=7, c="#39434d", marker="s", depthshade=False)
        # The trajectory: the same walk as a connected polyline.
        ax.plot(px, py, pz, color=line_color, linewidth=1.4, alpha=0.9)

        # Direction arrows sampled along the path.
        if len(px) > 1:
            arrow_count = min(22, len(px) - 1)
            idx = np.unique(np.linspace(0, len(px) - 2, arrow_count).astype(int))
            step = max(
                1.0,
                float(np.max([px.max() - px.min(), py.max() - py.min(), pz.max() - pz.min()])) * 0.05,
            )
            ax.quiver(
                px[idx], py[idx], pz[idx],
                px[idx + 1] - px[idx], py[idx + 1] - py[idx], pz[idx + 1] - pz[idx],
                length=step, normalize=True, color=line_color,
                arrow_length_ratio=0.35, linewidth=1.0, alpha=0.95,
            )
        legend_handles.append(
            Line2D([0], [0], color=line_color, lw=1.6, label=f"{player_name} trajectory")
        )

    legend_handles.append(
        Line2D([0], [0], marker="s", color="none", markerfacecolor="#39434d",
               markeredgecolor="none", markersize=6, label="Mined blocks (tunnel)")
    )

    # ----- ore discoveries: colour by ore, shape/fill by exposure ----------
    ore_order = list(dict.fromkeys(discoveries["ore_id"].tolist())) if not discoveries.empty else []
    suspicious_mask = []
    for ore_index, ore in enumerate(ore_order):
        base = ore_color(ore, ore_index)
        subset = discoveries[discoveries["ore_id"] == ore]
        for hidden in (False, True):
            group = subset[(subset["discovery_exposure"] == HIDDEN_EXPOSURE) == hidden]
            if group.empty:
                continue
            gx, gy, gz = _to_plot(group)
            all_points.append(np.column_stack([gx, gy, gz]))
            sizes = 55 + 18 * group["vein_size"].to_numpy(dtype=float)
            face = shade(base, 0.55) if hidden else base
            ax.scatter(
                gx, gy, gz, s=sizes, marker=HIDDEN_MARKER if hidden else EXPOSED_MARKER,
                facecolors=[face], edgecolors="#f2f2f2", linewidths=1.1,
                depthshade=False, zorder=5,
            )
            legend_handles.append(
                Line2D([0], [0], marker=HIDDEN_MARKER if hidden else EXPOSED_MARKER,
                       color="none", markerfacecolor=face, markeredgecolor="#f2f2f2",
                       markersize=9, label=f"{ore} ({'hidden' if hidden else 'exposed'})")
            )

    # ----- suspicious targeting vectors -----------------------------------
    # For a discovery whose move_alignment_deg is small (and not NULL), draw an
    # arrow from the player's previous mined position to the ore. NULL means
    # "not measurable" and is deliberately skipped rather than treated as zero.
    vector_count = 0
    if not discoveries.empty:
        for player_id, _ in players:
            mined = mining_by_player.get(player_id)
            if mined is None or mined.empty:
                continue
            mined = mined.sort_values("occurred_at", kind="stable")
            times = mined["occurred_at"].to_numpy()
            positions = mined[["x", "y", "z"]].to_numpy(dtype=float)
            player_disc = discoveries[discoveries["player_id"] == player_id]
            for _, row in player_disc.iterrows():
                angle = row["move_alignment_deg"]
                if angle is None or (isinstance(angle, float) and np.isnan(angle)):
                    continue
                if float(angle) > max_align_deg:
                    continue
                # Last mining event strictly before the discovery = approach point.
                slot = int(np.searchsorted(times, row["occurred_at"], side="left")) - 1
                if slot < 0:
                    continue
                ax0, ay0, az0 = positions[slot]
                # Map the vector into plot space the same way as the points.
                ax.quiver(
                    ax0, az0, ay0,
                    float(row["x"]) - ax0, float(row["z"]) - az0, float(row["y"]) - ay0,
                    length=1.0, normalize=False, color=_TARGETING_COLOR,
                    arrow_length_ratio=0.14, linewidth=1.6, alpha=0.95,
                )
                ax.scatter([ax0], [az0], [ay0], s=28, marker="x",
                           c=_TARGETING_COLOR, depthshade=False)
                vector_count += 1
    if vector_count:
        legend_handles.append(
            Line2D([0], [0], color=_TARGETING_COLOR, lw=1.6, linestyle=":",
                   label=f"Targeting vector (move \u2264 {max_align_deg:.0f}\u00b0)")
        )

    # ----- axes, legend, titles -------------------------------------------
    if all_points:
        stacked = np.vstack(all_points)
        _equalise_axes(ax, stacked)

    ax.set_xlabel("X (blocks)", color="#dddddd", labelpad=10)
    ax.set_ylabel("Z (blocks)", color="#dddddd", labelpad=10)
    ax.set_zlabel("Y (up)", color="#dddddd", labelpad=10)
    ax.set_title(
        title,
        color="#f5f5f5", fontsize=14, pad=18,
    )
    if legend_handles:
        ax.legend(
            handles=legend_handles, loc="upper left", bbox_to_anchor=(0.0, 1.0),
            facecolor="#161b21", edgecolor="#333b44", labelcolor="#e6e6e6",
            fontsize=8, framealpha=0.9,
        )
    fig.tight_layout()

    if output:
        fig.savefig(output, dpi=140, facecolor=fig.get_facecolor())
        print(f"Wrote {output}")
    if show:
        plt.show()
    plt.close(fig)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="xray_visualizer",
        description=(
            "Render an interactive 3D view of a player's mining for the XRay "
            "anti-cheat plugin: trajectory, tunnels, ore discoveries and "
            "suspicious targeting vectors."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--database-url", "--db",
        dest="database_url", default=None,
        help="SQLAlchemy-style URL or a path to a SQLite file, e.g. "
             "sqlite:///xray.db, postgresql://user:pw@host/db, mariadb://user:pw@host/db.",
    )
    parser.add_argument(
        "--player", default=None,
        help="Player UUID or name. Defaults to every known player.",
    )
    parser.add_argument("--world", default=None, help="Restrict to one world_key.")
    parser.add_argument(
        "--ore", action="append", default=None, metavar="ORE_ID",
        help="Restrict to an ore type. Repeat for several (diamond, emerald, ancient_debris, ...).",
    )
    parser.add_argument(
        "--since", type=parse_timestamp, default=None,
        help="Only data at/after this time (epoch millis or ISO-8601).",
    )
    parser.add_argument(
        "--until", type=parse_timestamp, default=None,
        help="Only data at/before this time (epoch millis or ISO-8601).",
    )
    exposure = parser.add_mutually_exclusive_group()
    exposure.add_argument(
        "--hidden-only", action="store_const", const="hidden", dest="exposure",
        help="Show only hidden ore discoveries (discovery_exposure = HIDDEN).",
    )
    exposure.add_argument(
        "--exposed-only", action="store_const", const="exposed", dest="exposure",
        help="Show only exposed ore discoveries (everything except HIDDEN).",
    )
    parser.set_defaults(exposure="both")
    parser.add_argument(
        "--min-confidence", type=float, default=None, metavar="[0-1]",
        help="Minimum per-discovery evidence_discount to include.",
    )
    parser.add_argument(
        "--max-align-deg", type=float, default=30.0, metavar="DEG",
        help="Draw a targeting vector when move_alignment_deg is at most this.",
    )
    parser.add_argument(
        "--output", "-o", default=None, metavar="PNG",
        help="Save the scene to a PNG file (works headlessly).",
    )
    parser.add_argument(
        "--no-interactive", action="store_true",
        help="Do not open a window; implies a headless (Agg) backend.",
    )
    parser.add_argument(
        "--demo", action="store_true",
        help="Use a synthetic in-memory SQLite dataset (no server required).",
    )
    return parser


def _validate_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    if args.demo and args.database_url:
        parser.error("--demo and --database-url are mutually exclusive.")
    if not args.demo and not args.database_url:
        parser.error("--database-url is required unless --demo is given.")
    if args.min_confidence is not None and not (0.0 <= args.min_confidence <= 1.0):
        parser.error("--min-confidence must be within [0, 1].")
    if args.since is not None and args.until is not None and args.since > args.until:
        parser.error("--since must not be later than --until.")


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    _validate_args(parser, args)

    # Matplotlib must know whether it is headless before pyplot is imported.
    show = not args.no_interactive and args.output is None
    plt = _setup_matplotlib(no_interactive=args.no_interactive or args.output is not None)

    database = build_demo_database() if args.demo else open_database(args.database_url)

    # Resolve the set of players to draw.
    if args.player:
        player_ids = [resolve_player(database, args.player)]
    else:
        known = list_players(database)
        if known.empty:
            raise SystemExit("No players found in the database.")
        player_ids = known["id"].tolist()

    name_by_id = {str(row["id"]): str(row["name"]) for _, row in list_players(database).iterrows()}
    players = [(pid, name_by_id.get(pid, pid[:8])) for pid in player_ids]

    mining_by_player: dict[str, pd.DataFrame] = {}
    discovery_frames: list[pd.DataFrame] = []
    for pid in player_ids:
        mining_by_player[pid] = load_mining_events(
            database, pid, world=args.world, since=args.since, until=args.until,
        )
        discovery_frames.append(load_discoveries(
            database, pid,
            world=args.world, ores=args.ore or (), since=args.since, until=args.until,
            exposure=args.exposure, min_confidence=args.min_confidence,
        ))
    discoveries = (
        pd.concat(discovery_frames, ignore_index=True) if discovery_frames else pd.DataFrame()
    )

    if discoveries.empty and not any(not f.empty for f in mining_by_player.values()):
        print("No data matched the given filters; nothing to draw.", file=sys.stderr)
        return 2

    filters = []
    if args.world:
        filters.append(f"world={args.world}")
    if args.ore:
        filters.append("ores=" + ",".join(args.ore))
    if args.since is not None:
        filters.append("since=" + format_millis(args.since))
    if args.until is not None:
        filters.append("until=" + format_millis(args.until))
    if args.exposure != "both":
        filters.append(args.exposure + "-only")
    if args.min_confidence is not None:
        filters.append(f"confidence\u2265{args.min_confidence:g}")

    subtitle = " | ".join(filters) if filters else "all data"
    title = (
        f"XRay AntiCheat \u2014 {', '.join(name for _, name in players)}\n"
        f"{database.label}  \u2022  {subtitle}"
    )

    render_scene(
        plt,
        players=players,
        mining_by_player=mining_by_player,
        discoveries=discoveries,
        title=title,
        max_align_deg=args.max_align_deg,
        output=args.output,
        show=show,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
