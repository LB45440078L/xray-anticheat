# Database

XRay AntiCheat persists everything through a single, **dialect-neutral** schema that executes
unchanged on SQLite, MariaDB/MySQL and PostgreSQL. This document describes the schema table by
table, the index that serves each query, the portability decisions behind the DDL, the
forward-only migration mechanism, retention, and how to set up each engine.

The DDL lives in one file: `xray-persistence/src/main/resources/migrations/V1__initial_schema.sql`.
Read it alongside this document — the comments in the SQL are the primary source.

---

## 0. Testing status (read this first)

- **SQLite is the only engine covered by automated tests.** The seven tests in
  `PersistenceIntegrationTest` run against a real embedded SQLite database file created by the
  real `MigrationRunner`.
- **MariaDB and PostgreSQL share the same code paths but are not runtime-tested.** They use the
  same repository classes, the same SQL strings and the same dialect-neutral DDL; only driver
  selection and connection parameters differ (`Dialect`, `HikariConnectionProvider`). Verifying
  them requires a live server and is left to deployment. The Javadoc of the integration test
  states this plainly, and it is repeated here rather than hidden.

Two consequences of that gap that an operator should know:

1. A column-order or SQL-state assumption that SQLite tolerates might behave differently on the
   other engines; the code avoids engine-specific constructs precisely to make this unlikely,
   but "unlikely" is not "verified".
2. SQLite-specific tuning (busy timeout, pool cap) does not apply to the server engines; they
   rely on driver-side prepared-statement caching instead (§6).

---

## 1. Portability decisions

The header of `V1__initial_schema.sql` documents the reasoning; this is the summary. The schema
is deliberately conservative about types, because maintaining three divergent schema files is a
reliable way to end up with three subtly different databases and three sets of bugs.

| Decision | Why |
| --- | --- |
| **Application-generated UUID keys** stored as `VARCHAR(36)` | The application already owns identity — it knows the player's UUID before any row exists. Avoiding `IDENTITY` / `AUTO_INCREMENT` / `SERIAL` removes the single largest source of dialect-specific DDL. |
| **`BIGINT` epoch-milliseconds timestamps (UTC)** | Native `TIMESTAMP` types disagree about timezone handling and precision across all three engines, and evidence decay is computed in the application anyway, so an unambiguous integer is simpler and safer. |
| **`INTEGER` 0/1 booleans** | SQLite has no boolean type, and PostgreSQL accepts `0`/`1` in an integer column. |
| **`DOUBLE PRECISION` for floating point** | All three engines accept it. |
| **`BIGINT` coordinates** | A world coordinate can legitimately exceed the 32-bit range in modded/amplified worlds, and a coordinate that silently wrapped would corrupt every geometric analysis derived from it. Vanilla stays well inside 32 bits; the headroom is insurance. |
| **`VARCHAR` for bounded identifiers, `TEXT` for free-form strings** | Identifiers (`world_key`, `ore_id`, `origin`, `evidence_strength`) are bounded; explanations, material keys and summaries are free-form. |
| **No engine-specific storage options, collations or index types** | Keeps the DDL portable. |
| **No foreign keys** | The schema is intentionally constraint-light so retention deletes are never blocked by referential-integrity checks. Cascades are done in application code instead — notably `JdbcSuspicionRepository.deleteOlderThan`, which sweeps orphaned `evidence_events` in the same transaction by anti-join. |
| **`CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`** | Makes version 1 idempotent against a database that predates the migration ledger, and makes a re-run of the same migration harmless. |

Because the DDL is dialect-neutral, supporting three engines does **not** mean three sets of
SQL. What differs between engines is confined to driver selection, connection parameters and a
handful of behavioural quirks, all handled in `Dialect` and `HikariConnectionProvider`.

---

## 2. Tables

The version-1 schema creates fourteen tables. For each: the columns, why the table exists, and
the indexes with the query each serves. `IF NOT EXISTS` is omitted below for brevity.

### `players` — one row per known player

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | the player UUID |
| `name` | `VARCHAR(64)` | mutable display attribute, updated on sight |
| `first_seen` | `BIGINT` | epoch millis, preserved across updates |
| `last_seen` | `BIGINT` | epoch millis, updated on sight |

**Rationale.** Identity is the UUID, never the name: names change and are attacker-controlled at
registration. The name is stored once, in one place, rather than denormalised onto every event
row, so a rename does not leave a trail of stale names across millions of historical rows. The
`JdbcPlayerRepository` updater preserves `first_seen` and only rewrites `name`/`last_seen`.

**Indexes.**
- `idx_players_last_seen (last_seen)` — serves `PlayerRepository.mostRecentlySeen(limit)`
  (`ORDER BY last_seen DESC LIMIT ?`) for moderator listings and the GUI.

### `player_sessions` — one row per contiguous session in one world

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `player_id` | `VARCHAR(36)` | |
| `world_key` | `VARCHAR(128)` | |
| `started_at` | `BIGINT` | |
| `ended_at` | `BIGINT` | nullable while open |
| `blocks_mined` | `DOUBLE PRECISION` | aggregate, default 0 |
| `distance_travelled` | `DOUBLE PRECISION` | aggregate, default 0 |

**Rationale.** Aggregates are stored rather than derived so that session summaries remain
available after the fine-grained observations have been pruned by retention.

**Status.** The table exists in the schema but **no repository writes or reads it**. Session
tracking itself is implemented, but it lives entirely in memory (`PlayerSession`/`SessionRegistry`
in `xray-spigot`), so nothing currently persists per-session aggregates. The table is reserved for
a future session-persistence feature.

**Indexes.**
- `idx_sessions_player (player_id, started_at)` — a player's sessions in time order.
- `idx_sessions_open (player_id, world_key, ended_at)` — finding the open session for a player
  in a world (`ended_at IS NULL`).

### `world_modifications` — the excavation ledger

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `world_key` | `VARCHAR(128)` | |
| `x`, `y`, `z` | `BIGINT` | exact block coordinate |
| `origin` | `VARCHAR(32)` | a `MiningOrigin` name |
| `actor_id` | `VARCHAR(36)` | nullable when the actor is unknown |
| `removed_at` | `BIGINT` | epoch millis |

**Rationale.** This is the table that lets the exposure analyser answer "who removed the block
that used to be here, and when?". It is the difference between "this player found a hidden ore"
and "this player walked into a hole somebody else dug" — the single most important distinction
for avoiding accusations against players who merely explore inhabited ground. It is
append-mostly, consulted by exact `(world, x, y, z)` lookup, and the largest table over time,
which is why it has its own retention policy and a covering index.

**Indexes.**
- `idx_world_mod_pos (world_key, x, y, z)` — the covering index for
  `WorldModificationRepository.originAt` / `removedAtEpochMillis`, which are exact-coordinate
  lookups on the analysis path.
- `idx_world_mod_removed (removed_at)` — retention pruning
  (`DELETE FROM world_modifications WHERE removed_at < ?`).
- `idx_world_mod_actor (actor_id, removed_at)` — "what has this player dug recently", useful for
  moderator inspection.

### `mining_events` — every attributed block break

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `player_id` | `VARCHAR(36)` | |
| `session_id` | `VARCHAR(36)` | nullable; `JdbcMiningEventRepository.saveAll` currently writes `NULL` |
| `world_key` | `VARCHAR(128)` | |
| `x`, `y`, `z` | `BIGINT` | |
| `block_key` | `VARCHAR(128)` | namespaced material key |
| `origin` | `VARCHAR(32)` | a `MiningOrigin` name |
| `tick` | `BIGINT` | server tick, for ordering |
| `occurred_at` | `BIGINT` | epoch millis |

**Rationale.** Fine-grained by design — one row per block — because the trajectory and tunnel
geometry are reconstructed from that sequence; an aggregate would make it impossible. The cost
is volume, which is why this table has the shortest retention window of any in the schema.

**Indexes.**
- `idx_mining_player_time (player_id, occurred_at)` — `findRecent` (a player's recent breaks,
  newest first) and `countSince`.
- `idx_mining_session (session_id)` — joining breaks to a session.
- `idx_mining_world_pos (world_key, x, y, z)` — "who broke this block", the reverse lookup.

### `ore_discoveries` — one row per ore vein encounter

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `player_id` | `VARCHAR(36)` | |
| `session_id` | `VARCHAR(36)` | nullable |
| `world_key` | `VARCHAR(128)` | |
| `ore_id` | `VARCHAR(64)` | canonical ore id |
| `x`, `y`, `z` | `BIGINT` | the block the player broke |
| `discovery_exposure` | `VARCHAR(32)` | an `ExposureState` name |
| `vein_size` | `INTEGER` | |
| `hidden_vein_blocks` | `INTEGER` | |
| `exposed_vein_blocks` | `INTEGER` | |
| `blocks_since_previous` | `DOUBLE PRECISION` | effort since the previous discovery |
| `distance_since_previous` | `DOUBLE PRECISION` | travel since the previous discovery |
| `move_alignment_deg` | `DOUBLE PRECISION` **nullable** | |
| `look_alignment_deg` | `DOUBLE PRECISION` **nullable** | |
| `evidence_discount` | `DOUBLE PRECISION` default 1.0 | |
| `occurred_at` | `BIGINT` | |

**Rationale.** One row per **vein**, not per ore block: a vein is one statistical observation,
and storing its blocks individually would inflate the sample size (pseudo-replication) and
multiply storage by the vein size. The table is also the data source for the Python visualiser,
which is why the full geometric and alignment context is stored rather than only the verdict.

The alignment columns are nullable **on purpose**: they are `NULL` when the player's path history
was too short to measure an approach, which is a genuinely different state from "measured, and
the angle was zero". `JdbcOreDiscoveryRepository` reconstructs a `NULL` as
`TrajectoryAnalysis.Approach.noData()`, never as a zero angle — collapsing them would fabricate
perfectly-aligned approaches and manufacture suspicion out of missing data.

**Indexes.**
- `idx_ore_player_time (player_id, occurred_at)` — `findRecent`.
- `idx_ore_world_type (world_key, ore_id)` — `findByWorldAndOre`, used for cross-player
  comparison.
- `idx_ore_world_pos (world_key, x, y, z)` — locating a discovery by position.

### `ore_veins` — reconstructed vein geometry

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `discovery_id` | `VARCHAR(36)` | references a discovery |
| `ore_id` | `VARCHAR(64)` | |
| `world_key` | `VARCHAR(128)` | |
| `block_count` | `INTEGER` | |
| `exposed_count` | `INTEGER` | |
| `hidden_count` | `INTEGER` | |
| `centroid_x/y/z` | `DOUBLE PRECISION` | |
| `axis_x/y/z` | `DOUBLE PRECISION` | dominant axis |
| `linearity` | `DOUBLE PRECISION` | |
| `planarity` | `DOUBLE PRECISION` | |
| `extent` | `DOUBLE PRECISION` | |
| `truncated` | `INTEGER` default 0 | 0/1 boolean |

**Rationale.** Kept separate from `ore_discoveries` because it is derived data that is
expensive to recompute (a chunk flood fill) but only needed for inspection and the visualiser,
so it can be pruned independently. The `VeinObservation.VeinShape` record is the in-memory
counterpart.

**Status.** The table exists but **no repository writes or reads it yet**; there is no
`OreVeinRepository` in `xray-core`.

**Indexes.**
- `idx_veins_discovery (discovery_id)` — fetching the vein for a discovery.

### `suspicion_snapshots` — the full assessment at a point in time

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `player_id` | `VARCHAR(36)` | |
| `world_key` | `VARCHAR(128)` | |
| `evaluated_at` | `BIGINT` | |
| `prior_log_odds` | `DOUBLE PRECISION` | |
| `effective_log_odds` | `DOUBLE PRECISION` | post-shrink, post-decay |
| `posterior_log_odds` | `DOUBLE PRECISION` | prior + effective |
| `suspicion_score` | `DOUBLE PRECISION` | logistic of posterior |
| `statistical_confidence` | `DOUBLE PRECISION` | |
| `decay_factor` | `DOUBLE PRECISION` | |
| `sample_size` | `INTEGER` | |
| `independent_groups` | `INTEGER` | |
| `evidence_strength` | `VARCHAR(32)` | an `EvidenceStrength` name |

**Rationale.** Storing the decomposition (prior / effective / posterior), not only the final
score, is what makes an assessment auditable after the fact: a moderator can see how much of the
number came from evidence and how much from the prior. The full `SuspicionSnapshot` round-trips
through `JdbcSuspicionRepository`.

**Indexes.**
- `idx_snapshots_player_time (player_id, evaluated_at)` — `history`/`latest` (a player's
  snapshots, newest first) and retention pruning (`evaluated_at < ?`).
- `idx_snapshots_strength (evidence_strength, evaluated_at)` — "which players reached STRONG or
  above recently", for the moderator list.

### `evidence_events` — one row per component contribution within a snapshot

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `snapshot_id` | `VARCHAR(36)` | references a snapshot (no FK) |
| `component_id` | `VARCHAR(64)` | e.g. `hidden-discovery-rate` |
| `independent_group` | `VARCHAR(64)` | e.g. `discovery-rate` |
| `log_likelihood_ratio` | `DOUBLE PRECISION` | |
| `reliability` | `DOUBLE PRECISION` | |
| `sample_size` | `INTEGER` | |
| `explanation` | `TEXT` | the sentence a moderator reads |

**Rationale.** The explanation is stored verbatim so that an alert can always be traced back to
the sentence a moderator would have read at the time, even after the component's code has
changed. Snapshot and evidence rows are written in one transaction, so a stored assessment is
always complete or absent — never half-present.

**Indexes.**
- `idx_evidence_snapshot (snapshot_id)` — hydrating a snapshot's contributions.
- `idx_evidence_component (component_id, log_likelihood_ratio)` — "which components most often
  fire", for calibration analysis.

### `ban_wave_candidates` — players held for deferred enforcement

| Column | Type | Notes |
| --- | --- | --- |
| `player_id` | `VARCHAR(36)` **PK** | one candidate per player |
| `world_key` | `VARCHAR(128)` | |
| `first_detected` | `BIGINT` | preserved across updates |
| `last_detected` | `BIGINT` | |
| `peak_suspicion` | `DOUBLE PRECISION` | |
| `peak_confidence` | `DOUBLE PRECISION` | |
| `peak_strength` | `VARCHAR(32)` | strongest band ever seen |
| `sample_size` | `INTEGER` | |
| `independent_groups` | `INTEGER` | |
| `evidence_summary` | `TEXT` | human-readable digest |

**Rationale.** `peak_*` rather than `latest_*`: a single strong detection is not erased by a
later quiet session, so a cheater cannot clear their record by behaving for a day. The primary
key is the player, so there is exactly one candidate per player, and re-recording strengthens
rather than replaces. The "keep the stronger" comparison is done in Java (`BanWaveCandidate
.mergedWith`) on the enum's declaration order, *not* as a string comparison in SQL, because the
enum names do not sort in strength order (`WEAK` sorts alphabetically after `VERY_STRONG` while
being far weaker). The integration test deliberately orders records so a naive string comparison
would fail.

**Indexes.**
- `idx_candidates_detected (last_detected)` — listing candidates by recency and retention
  pruning (`first_detected < ?`).
- `idx_candidates_strength (peak_strength, peak_confidence)` — filtering by strength for a wave.

### `ban_waves` — a record of every wave that ran

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `planned_at` | `BIGINT` | |
| `executed_at` | `BIGINT` | nullable while proposed |
| `candidate_count` | `INTEGER` | |
| `automatic_ban` | `INTEGER` | 0/1 |
| `summary` | `TEXT` | |

**Rationale.** The record of past waves exists so the inter-wave interval survives a server
restart, and as the audit trail: "when did this player get removed, and on whose authority?"

**Indexes.**
- `idx_ban_waves_executed (executed_at)` — `lastExecutedWaveAt` (`MAX(executed_at) WHERE
  executed_at IS NOT NULL`).

### `moderator_actions` — who did what to whom, and why

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `VARCHAR(36)` PK | |
| `player_id` | `VARCHAR(36)` | |
| `moderator_id` | `VARCHAR(36)` | |
| `action` | `VARCHAR(32)` | |
| `note` | `TEXT` | free-text reason |
| `performed_at` | `BIGINT` | |

**Rationale.** Every enforcement action is recorded with the moderator's identifier and a
free-text note so the server can answer "why was this player banned?" months later.

**Status.** Implemented. `JdbcModeratorActionRepository` writes and reads it, driven by
`EnforcementService.recordModeratorAction` (automated kicks, manual kicks and bans, ban waves,
flags and moderator notes). A `NULL` `moderator_id` means the action was automatic, which is
stored deliberately rather than encoded as a sentinel player.

**Indexes.**
- `idx_mod_actions_player (player_id, performed_at)` — a player's action history.
- `idx_mod_actions_moderator (moderator_id, performed_at)` — a moderator's action history.

### `world_analysis` — one row per analysed world/dimension

| Column | Type | Notes |
| --- | --- | --- |
| `world_key` | `VARCHAR(128)` PK | |
| `analysed_at` | `BIGINT` | |
| `ore_ids` | `TEXT` | |
| `notes` | `TEXT` | |

**Rationale.** Kept so an administrator can see which worlds have been analysed and with which
ore distributions, rather than having to infer it.

**Status.** Table exists; no repository yet.

### `plugin_metadata` — small key/value store

| Column | Type | Notes |
| --- | --- | --- |
| `meta_key` | `VARCHAR(128)` PK | |
| `meta_value` | `TEXT` | |
| `updated_at` | `BIGINT` | |

**Rationale.** For plugin-level state (ban-wave scheduling markers, calibration counters, schema
bookkeeping beyond `schema_migrations`).

**Status.** Table exists; no repository yet.

### `schema_migrations` — the applied-migration ledger

| Column | Type | Notes |
| --- | --- | --- |
| `version` | `INTEGER` PK | |
| `name` | `VARCHAR(128)` | |
| `applied_at` | `BIGINT` | |

**Rationale.** Never dropped, never rewritten: it is the only record of how the database reached
its current shape, and migrations are forward-only and additive. `MigrationRunner` recreates it
with `CREATE TABLE IF NOT EXISTS` before anything else, so it also exists on a database that
predates the ledger.

---

## 3. Full index inventory

| Index | Table | Columns | Query served |
| --- | --- | --- | --- |
| `idx_players_last_seen` | `players` | `last_seen` | recent players listing |
| `idx_sessions_player` | `player_sessions` | `player_id, started_at` | a player's sessions over time |
| `idx_sessions_open` | `player_sessions` | `player_id, world_key, ended_at` | find the open session |
| `idx_world_mod_pos` | `world_modifications` | `world_key, x, y, z` | ledger provenance lookup |
| `idx_world_mod_removed` | `world_modifications` | `removed_at` | retention prune |
| `idx_world_mod_actor` | `world_modifications` | `actor_id, removed_at` | "what did this player dig" |
| `idx_mining_player_time` | `mining_events` | `player_id, occurred_at` | recent breaks, count since |
| `idx_mining_session` | `mining_events` | `session_id` | breaks joined to a session |
| `idx_mining_world_pos` | `mining_events` | `world_key, x, y, z` | who broke this block |
| `idx_ore_player_time` | `ore_discoveries` | `player_id, occurred_at` | recent discoveries |
| `idx_ore_world_type` | `ore_discoveries` | `world_key, ore_id` | cross-player ore comparison |
| `idx_ore_world_pos` | `ore_discoveries` | `world_key, x, y, z` | discovery by position |
| `idx_veins_discovery` | `ore_veins` | `discovery_id` | vein for a discovery |
| `idx_snapshots_player_time` | `suspicion_snapshots` | `player_id, evaluated_at` | snapshot history; prune |
| `idx_snapshots_strength` | `suspicion_snapshots` | `evidence_strength, evaluated_at` | strongest recent assessments |
| `idx_evidence_snapshot` | `evidence_events` | `snapshot_id` | hydrate a snapshot |
| `idx_evidence_component` | `evidence_events` | `component_id, log_likelihood_ratio` | component calibration |
| `idx_candidates_detected` | `ban_wave_candidates` | `last_detected` | candidate listing; prune |
| `idx_candidates_strength` | `ban_wave_candidates` | `peak_strength, peak_confidence` | wave eligibility filter |
| `idx_ban_waves_executed` | `ban_waves` | `executed_at` | last-executed-wave lookup |
| `idx_mod_actions_player` | `moderator_actions` | `player_id, performed_at` | player action history |
| `idx_mod_actions_moderator` | `moderator_actions` | `moderator_id, performed_at` | moderator action history |

---

## 4. Migrations

### The mechanism

`io.xrayac.persistence.migration.MigrationRunner` applies forward-only migrations, exactly once
each.

1. It creates `schema_migrations` if absent.
2. It reads `migrations/index.txt` from the classpath — an explicit, one-file-per-line list.
3. It reads the `version` column of `schema_migrations` to find what has already been applied.
4. It applies each pending migration in **ascending version order**, each inside its own
   transaction: every statement in the file, then a row inserted into `schema_migrations`
   (`version`, `name`, `applied_at`). The migration and its ledger row commit together, so an
   interrupted upgrade leaves the database at a consistent version rather than half-migrated
   with no record of where it stopped.
5. If nothing is pending it logs the current version and changes nothing.

### Design rules

- **Forward-only.** Migrations are never rolled back automatically. A rollback that silently
  discards a table is a data-loss feature, not a safety one.
- **Additive.** Migrations must not drop or destructively alter data. Columns are added with
  defaults, tables are renamed rather than rewritten, and anything retired is emptied by the
  retention policy rather than by a migration.
- **Listed explicitly, not scanned.** The index is read from `index.txt` rather than by scanning
  the directory, because a directory scan does not work reliably inside a packaged JAR; the
  failure mode would be silently applying *no* migrations on a production server, which is far
  worse than maintaining one line per migration. If the index resolves to no migrations,
  `MigrationRunner` throws rather than running against an unknown schema.

### The SQL splitter

`MigrationRunner.splitStatements` strips comment lines whose first non-whitespace characters are
`--`, then splits on `;`. It is a deliberately simple parser, not a real SQL grammar: migration
files are authored by this project and contain no string literals with embedded semicolons, so a
full parser would be complexity without corresponding safety. **If you add a migration, do not
put a semicolon inside a string literal** unless you also extend the splitter.

### Adding a migration

1. Create `xray-persistence/src/main/resources/migrations/V<n>__<description>.sql`, where
   `<n>` is the next positive integer. The version is parsed from the name as the integer between
   `V` and the first `__`; a name that does not match `V<version>__<description>.sql` fails
   loudly at startup.
2. Write **additive** DDL only. New columns need a `DEFAULT`; new tables use
   `CREATE TABLE IF NOT EXISTS`; new indexes use `CREATE INDEX IF NOT EXISTS`. Do not drop or
   rename destructively.
3. Add the file name on its own line to `migrations/index.txt` (comments start with `#`). Order
   in the file does not matter — versions are sorted — but keeping it ascending helps humans.
4. If you add a table or column that a repository uses, update the repository in the same change.
5. Add an integration-test assertion if the change affects a tested path. `PersistenceIntegrationTest
   .schemaIsComplete` lists the expected tables and will need the new table added.

Current index (verbatim):

```
V1__initial_schema.sql
```

---

## 5. Retention

Nothing is kept forever. Retention is expressed in `config.yml` under `retention:` and executed
through the repositories' `deleteOlderThan(Instant)` methods:

| Config key | Default (days) | Repository method | Table |
| --- | --- | --- | --- |
| `retention.mining-events-days` | 7 | `MiningEventRepository.deleteOlderThan` | `mining_events` |
| `retention.ore-discoveries-days` | 90 | `OreDiscoveryRepository.deleteOlderThan` | `ore_discoveries` |
| `retention.suspicion-snapshots-days` | 180 | `SuspicionRepository.deleteOlderThan` | `suspicion_snapshots` (+ orphan sweep of `evidence_events`) |
| `retention.world-modifications-days` | 30 | `WorldModificationRepository.deleteOlderThan` | `world_modifications` |
| — | — | `ban_waves` is **not pruned, by design** | the enforcement audit trail; a few hundred rows a year |
| `retention.enabled` | true | — | master switch |
| `retention.prune-hour` | 4 | — | hour of day the daily prune runs (server local time) |

Because there are no foreign keys, cascades are done in application code. The clearest example
is `JdbcSuspicionRepository.deleteOlderThan`, which deletes old snapshots and then sweeps
`evidence_events` whose `snapshot_id` no longer exists, in the same transaction
(`DELETE FROM evidence_events WHERE snapshot_id NOT IN (SELECT id FROM suspicion_snapshots)`).

**Status.** The `deleteOlderThan` methods exist, are unit/integration-tested (`retentionPruning`
test), and are driven by the `pruneRetention` task in `XRayAntiCheatPlugin`. That task is
registered asynchronously every 72,000 ticks (about an hour) and only acts when the server-local
hour equals `retention.prune-hour`; it prunes `mining_events`, `ore_discoveries`,
`suspicion_snapshots` (with their orphaned `evidence_events`) and `world_modifications`.

One gap, stated plainly: `ban_wave_candidates` is dropped by the ban-wave planner's own
`ban-wave.candidate-ttl-hours` during planning, not by this retention task, so a candidate that is
never planned over can outlive its TTL in the table.

`ban_waves` is deliberately not pruned. It is the enforcement audit trail — the answer to "when was
this player removed, and on what evidence?" — and a wave runs at most daily, so the table holds a few
hundred rows a year. Buying back a few kilobytes at the cost of the accountability record would be a
poor trade, so the former `retention.ban-waves-days` key has been removed rather than left implying
the records expire.

The related in-memory ledger retention (`performance.ledger-retention-hours`, default 72) is
separate: it bounds the ledger held *in memory*, not the persisted table. See
[`PERFORMANCE.md`](PERFORMANCE.md).

---

## 6. Setting up each engine

The plugin selects its engine from `database.yml` under `storage.type` (`sqlite`, `mariadb` or
`postgresql`). `ConfigLoader.loadDatabase` builds the `DatabaseConfig`; `Dialect.fromJdbcUrl`
resolves the driver from the URL prefix, so the URL and the driver can never disagree.

### SQLite (default; needs nothing installed)

```yaml
storage:
  type: sqlite
  sqlite:
    file: "plugins/XRayAntiCheat/xray.db"   # relative to the server working directory
```

- Driver: `org.sqlite.JDBC`. Not bundled in the plugin jar — the server fetches `sqlite-jdbc` on
  first start from the `libraries:` list in `plugin.yml`, which is what keeps the jar at ~330 KB
  instead of 14 MB. See `docs/ADMIN_GUIDE.md` for offline servers.
- The pool is capped at four (SQLite is a single file with a database-level write lock; a larger
  pool buys nothing and can increase contention).
- Write-ahead logging and a writer busy-timeout are applied to every connection, as datasource
  properties: `journal_mode=WAL` and `busy_timeout=5000`.
  - WAL is what stops a background write from blocking the server thread's next read. Without it,
    this plugin's own persistence schedule would periodically stall the tick loop — the one thing the
    design exists to avoid.
  - `busy_timeout` makes a competing writer wait rather than fail immediately with `SQLITE_BUSY`,
    which would otherwise surface as spuriously dropped observations.
  - Neither is configurable, because neither is optional. They are supplied as connection properties
    rather than as connection-init SQL because HikariCP accepts only a single init statement and the
    two pragmas cannot share one; the SQLite driver reads these pragma names directly.
  - Verified empirically, not merely asserted: reading `PRAGMA journal_mode` back from a live
    connection opened by the shipped jar returns `wal`, and `PRAGMA busy_timeout` returns `5000`.
    An earlier revision of this document recorded the opposite as a known discrepancy (the code set
    only `busy_timeout`); that has been fixed and the check is now part of artefact verification.

### MariaDB / MySQL

```yaml
storage:
  type: mariadb
  mariadb:
    host: "localhost"
    port: 3306
    database: "xray_anticheat"
    username: "xray"
    password: ""                                   # prefer the environment variable
    password-environment-variable: "XRAY_DB_PASSWORD"
    connection-parameters: "useServerPrepStmts=true&rewriteBatchedStatements=true&useUnicode=true&characterEncoding=utf8"
  pool:
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout-millis: 10000
    batch-size: 500
```

Create the database and grant a user before first start: `CREATE DATABASE xray_anticheat
CHARACTER SET utf8;` then `GRANT ALL ON xray_anticheat.* TO 'xray'@'%' IDENTIFIED BY '…';`.
The schema is created automatically by the migration runner unless
`storage.migrations.run-on-startup` is false.

The connection parameters matter for throughput: `useServerPrepStmts` and
`rewriteBatchedStatements` together are what make bulk inserts fast on this engine.

### PostgreSQL

```yaml
storage:
  type: postgresql
  postgresql:
    host: "localhost"
    port: 5432
    database: "xray_anticheat"
    username: "xray"
    password: ""
    password-environment-variable: "XRAY_DB_PASSWORD"
    connection-parameters: "reWriteBatchedInserts=true"
  pool:
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout-millis: 10000
    batch-size: 500
```

Create the database and user (`CREATE DATABASE xray_anticheat;` `CREATE USER xray WITH PASSWORD
'…';` `GRANT ALL PRIVILEGES ON DATABASE xray_anticheat TO xray;`). `reWriteBatchedInserts=true`
is the PostgreSQL equivalent of the MariaDB batch-rewrite flag.

### Credentials

A password may be written in `database.yml`, but the environment variable named by
`password-environment-variable` takes precedence when set; if the variable is unset the loader
warns and falls back to the file. Configuration files end up in backups, version control and
support bundles, so credentials should not be in them.

### Failure to connect

The pool is created with `initializationFailTimeout = -1`, so an unreachable database does not
prevent the plugin from starting: it logs, retries in the background, and runs with reduced
function. `storage.fail-safe.continue-without-database` controls whether a failure disables the
plugin instead. See [`PERFORMANCE.md`](PERFORMANCE.md) §6 for the degraded-mode behaviour.

---

## 7. Known fidelity limitation: component metrics are not persisted

`EvidenceContribution` carries a `metrics` map — the named quantities quoted in an explanation
(for example `blocksMined`, `hiddenDiscoveries`, `observedMeanWaitBlocks`), used by the live GUI
and the Python visualiser. **The schema does not store that map, and
`JdbcSuspicionRepository` does not persist it.** A reconstructed contribution therefore carries
an **empty metrics map**, as the repository's own `Reconstruction fidelity` Javadoc states:

> Components' `metrics` maps are not persisted — the schema stores the explanation, which is what
> a moderator reads, but not the raw named quantities. Reconstructed contributions therefore
> carry an empty metrics map. … The limitation is recorded in `docs/DATABASE.md`.

This is a deliberate storage/fidelity trade-off: the explanation is the durable record, and
duplicating every named quantity per contribution would multiply the size of the largest audit
table for a field nothing reads after the fact. The consequence for consumers: if you rehydrate a
`suspicion_snapshots` history entry, each `EvidenceContribution.metrics()` returns `Map.of()`,
and `weightedLogLikelihoodRatio()` is still computable (it uses the stored `logLikelihoodRatio`
and `reliability`) but any metric-based reporting must come from the stored explanation text, not
from the metrics map. A future migration could add a `metrics` column (a JSON/`TEXT` blob) if
metric-level historical reporting becomes a requirement; it would be additive.
