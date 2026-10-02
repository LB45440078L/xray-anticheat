-- =====================================================================================
-- XRay AntiCheat — schema version 1
-- =====================================================================================
--
-- PORTABILITY NOTE (read before editing).
--
-- This DDL is written to execute unchanged on SQLite, MariaDB/MySQL and PostgreSQL. That is
-- achieved deliberately, at some cost in "native" type usage, because maintaining three
-- divergent schema files is a reliable way to end up with three subtly different databases and
-- three sets of bugs:
--
--   * Identifiers are application-generated UUIDs stored as VARCHAR(36). The application already
--     owns identity (it knows the player UUID before any row exists), and avoiding IDENTITY /
--     AUTO_INCREMENT / SERIAL removes the single largest source of dialect-specific DDL.
--   * Timestamps are BIGINT epoch milliseconds (UTC). Native TIMESTAMP types disagree about
--     timezone handling and precision across all three engines, and evidence decay is computed in
--     the application anyway, so storing an unambiguous integer is both simpler and safer.
--   * Booleans are INTEGER 0/1, since SQLite has no boolean type and PostgreSQL accepts 0/1 in an
--     integer column.
--   * Floating point is DOUBLE PRECISION, which all three accept.
--   * No engine-specific storage options, collations or index types are used.
--
-- Types are otherwise chosen for the access pattern: TEXT for free-form strings (explanations,
-- material keys), VARCHAR for bounded identifiers, BIGINT for coordinates (a world coordinate can
-- legitimately exceed the 32-bit range in modded worlds).
--
-- COORDINATE PRECISION: block coordinates are BIGINT, not INTEGER. Vanilla worlds stay inside
-- 32 bits easily, but modded or amplified worlds do not, and a coordinate that silently wraps
-- would corrupt every geometric analysis derived from it.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- players — one row per known player.
-- Identity is the UUID; the name is a mutable display attribute and is updated on sight.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS players (
    id            VARCHAR(36)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    first_seen    BIGINT       NOT NULL,
    last_seen     BIGINT       NOT NULL,
    CONSTRAINT pk_players PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_players_last_seen ON players (last_seen);


-- -------------------------------------------------------------------------------------
-- player_sessions — one row per contiguous play session in one world.
-- Aggregates are stored rather than derived so that session summaries remain available after
-- the fine-grained observations have been pruned by retention policy.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS player_sessions (
    id                 VARCHAR(36) NOT NULL,
    player_id          VARCHAR(36) NOT NULL,
    world_key          VARCHAR(128) NOT NULL,
    started_at         BIGINT      NOT NULL,
    ended_at           BIGINT,
    blocks_mined       DOUBLE PRECISION NOT NULL DEFAULT 0,
    distance_travelled DOUBLE PRECISION NOT NULL DEFAULT 0,
    CONSTRAINT pk_player_sessions PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_sessions_player ON player_sessions (player_id, started_at);
CREATE INDEX IF NOT EXISTS idx_sessions_open ON player_sessions (player_id, world_key, ended_at);


-- -------------------------------------------------------------------------------------
-- world_modifications — the excavation ledger.
--
-- This is the table that lets the exposure analyser answer "who removed the block that used to
-- be here, and when?". It is the difference between "this player found a hidden ore" and "this
-- player walked into a hole somebody else dug" — the single most important distinction for
-- avoiding accusations against players who merely explore inhabited ground.
--
-- It is append-mostly and consulted by exact (world, x, y, z) lookup, hence the covering index.
-- It is also the largest table over time, which is why it has its own retention policy.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS world_modifications (
    id         VARCHAR(36) NOT NULL,
    world_key  VARCHAR(128) NOT NULL,
    x          BIGINT      NOT NULL,
    y          BIGINT      NOT NULL,
    z          BIGINT      NOT NULL,
    origin     VARCHAR(32) NOT NULL,
    actor_id   VARCHAR(36),
    removed_at BIGINT      NOT NULL,
    CONSTRAINT pk_world_modifications PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_world_mod_pos ON world_modifications (world_key, x, y, z);
CREATE INDEX IF NOT EXISTS idx_world_mod_removed ON world_modifications (removed_at);
CREATE INDEX IF NOT EXISTS idx_world_mod_actor ON world_modifications (actor_id, removed_at);


-- -------------------------------------------------------------------------------------
-- mining_events — every block break we attributed to a tracked player.
--
-- Fine-grained by design (one row per block), because the trajectory and tunnel geometry are
-- reconstructed from it. Retention is therefore aggressive and configurable.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mining_events (
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
    CONSTRAINT pk_mining_events PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_mining_player_time ON mining_events (player_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_mining_session ON mining_events (session_id);
CREATE INDEX IF NOT EXISTS idx_mining_world_pos ON mining_events (world_key, x, y, z);


-- -------------------------------------------------------------------------------------
-- ore_discoveries — one row per ore vein encounter, the unit of evidence.
--
-- Note the deliberate absence of a per-block row: a vein is one observation. Storing per block
-- would both inflate the sample size (pseudo-replication) and multiply storage by the vein size.
--
-- The alignment columns are nullable on purpose: they are NULL when the player's path history was
-- too short to measure an approach, which is a genuinely different state from "measured, and the
-- angle was zero". Collapsing NULL to 0 would fabricate perfectly-aligned approaches and manufacture
-- suspicion out of missing data.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ore_discoveries (
    id                      VARCHAR(36)  NOT NULL,
    player_id               VARCHAR(36)  NOT NULL,
    session_id              VARCHAR(36),
    world_key               VARCHAR(128) NOT NULL,
    ore_id                  VARCHAR(64)  NOT NULL,
    x                       BIGINT       NOT NULL,
    y                       BIGINT       NOT NULL,
    z                       BIGINT       NOT NULL,
    discovery_exposure      VARCHAR(32)  NOT NULL,
    vein_size               INTEGER      NOT NULL,
    hidden_vein_blocks      INTEGER      NOT NULL,
    exposed_vein_blocks     INTEGER      NOT NULL,
    blocks_since_previous   DOUBLE PRECISION NOT NULL,
    distance_since_previous DOUBLE PRECISION NOT NULL,
    move_alignment_deg      DOUBLE PRECISION,
    look_alignment_deg      DOUBLE PRECISION,
    evidence_discount       DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    occurred_at             BIGINT       NOT NULL,
    CONSTRAINT pk_ore_discoveries PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ore_player_time ON ore_discoveries (player_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ore_world_type ON ore_discoveries (world_key, ore_id);
CREATE INDEX IF NOT EXISTS idx_ore_world_pos ON ore_discoveries (world_key, x, y, z);


-- -------------------------------------------------------------------------------------
-- ore_veins — the reconstructed vein geometry attached to a discovery.
-- Kept separate because it is derived data that is expensive to recompute (a chunk flood fill)
-- but only needed for inspection and the visualiser, so it can be pruned independently.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ore_veins (
    id                VARCHAR(36) NOT NULL,
    discovery_id      VARCHAR(36) NOT NULL,
    ore_id            VARCHAR(64) NOT NULL,
    world_key         VARCHAR(128) NOT NULL,
    block_count       INTEGER     NOT NULL,
    exposed_count     INTEGER     NOT NULL,
    hidden_count      INTEGER     NOT NULL,
    centroid_x        DOUBLE PRECISION NOT NULL,
    centroid_y        DOUBLE PRECISION NOT NULL,
    centroid_z        DOUBLE PRECISION NOT NULL,
    axis_x            DOUBLE PRECISION NOT NULL,
    axis_y            DOUBLE PRECISION NOT NULL,
    axis_z            DOUBLE PRECISION NOT NULL,
    linearity         DOUBLE PRECISION NOT NULL,
    planarity         DOUBLE PRECISION NOT NULL,
    extent            DOUBLE PRECISION NOT NULL,
    truncated         INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_ore_veins PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_veins_discovery ON ore_veins (discovery_id);


-- -------------------------------------------------------------------------------------
-- suspicion_snapshots — the full assessment at a point in time.
-- Storing the decomposition (prior / effective / posterior) rather than only the final score is
-- what makes an assessment auditable after the fact: a moderator can see how much of the number
-- came from evidence and how much from the prior.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS suspicion_snapshots (
    id                     VARCHAR(36) NOT NULL,
    player_id              VARCHAR(36) NOT NULL,
    world_key              VARCHAR(128) NOT NULL,
    evaluated_at           BIGINT      NOT NULL,
    prior_log_odds         DOUBLE PRECISION NOT NULL,
    effective_log_odds     DOUBLE PRECISION NOT NULL,
    posterior_log_odds     DOUBLE PRECISION NOT NULL,
    suspicion_score        DOUBLE PRECISION NOT NULL,
    statistical_confidence DOUBLE PRECISION NOT NULL,
    decay_factor           DOUBLE PRECISION NOT NULL,
    sample_size            INTEGER     NOT NULL,
    independent_groups     INTEGER     NOT NULL,
    evidence_strength      VARCHAR(32) NOT NULL,
    CONSTRAINT pk_suspicion_snapshots PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_snapshots_player_time ON suspicion_snapshots (player_id, evaluated_at);
CREATE INDEX IF NOT EXISTS idx_snapshots_strength ON suspicion_snapshots (evidence_strength, evaluated_at);


-- -------------------------------------------------------------------------------------
-- evidence_events — one row per component contribution within a snapshot.
-- The explanation is stored verbatim so that an alert can always be traced back to the sentence
-- a moderator would have read at the time, even after the component's code has changed.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS evidence_events (
    id                     VARCHAR(36) NOT NULL,
    snapshot_id            VARCHAR(36) NOT NULL,
    component_id           VARCHAR(64) NOT NULL,
    independent_group      VARCHAR(64) NOT NULL,
    log_likelihood_ratio   DOUBLE PRECISION NOT NULL,
    reliability            DOUBLE PRECISION NOT NULL,
    sample_size            INTEGER     NOT NULL,
    explanation            TEXT        NOT NULL,
    CONSTRAINT pk_evidence_events PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_evidence_snapshot ON evidence_events (snapshot_id);
CREATE INDEX IF NOT EXISTS idx_evidence_component ON evidence_events (component_id, log_likelihood_ratio);


-- -------------------------------------------------------------------------------------
-- ban_wave_candidates — players held for deferred enforcement.
-- peak_* rather than latest_*: a single strong detection is not erased by a later quiet session.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ban_wave_candidates (
    player_id          VARCHAR(36)  NOT NULL,
    world_key          VARCHAR(128) NOT NULL,
    first_detected     BIGINT       NOT NULL,
    last_detected      BIGINT       NOT NULL,
    peak_suspicion     DOUBLE PRECISION NOT NULL,
    peak_confidence    DOUBLE PRECISION NOT NULL,
    peak_strength      VARCHAR(32)  NOT NULL,
    sample_size        INTEGER      NOT NULL,
    independent_groups INTEGER      NOT NULL,
    evidence_summary   TEXT,
    CONSTRAINT pk_ban_wave_candidates PRIMARY KEY (player_id)
);

CREATE INDEX IF NOT EXISTS idx_candidates_detected ON ban_wave_candidates (last_detected);
CREATE INDEX IF NOT EXISTS idx_candidates_strength ON ban_wave_candidates (peak_strength, peak_confidence);


-- -------------------------------------------------------------------------------------
-- ban_waves — a record of every wave that ran, for auditing.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ban_waves (
    id            VARCHAR(36) NOT NULL,
    planned_at    BIGINT      NOT NULL,
    executed_at   BIGINT,
    candidate_count INTEGER   NOT NULL,
    automatic_ban INTEGER     NOT NULL DEFAULT 0,
    summary       TEXT,
    CONSTRAINT pk_ban_waves PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ban_waves_executed ON ban_waves (executed_at);


-- -------------------------------------------------------------------------------------
-- moderator_actions — who did what to whom, and why.
-- Every enforcement action is recorded with the moderator's own identifier and a free-text note so
-- that the server can answer "why was this player banned?" months later.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS moderator_actions (
    id           VARCHAR(36)  NOT NULL,
    player_id    VARCHAR(36)  NOT NULL,
    moderator_id VARCHAR(36)  NOT NULL,
    action       VARCHAR(32)  NOT NULL,
    note         TEXT,
    performed_at BIGINT       NOT NULL,
    CONSTRAINT pk_moderator_actions PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_mod_actions_player ON moderator_actions (player_id, performed_at);
CREATE INDEX IF NOT EXISTS idx_mod_actions_moderator ON moderator_actions (moderator_id, performed_at);


-- -------------------------------------------------------------------------------------
-- world_analysis — one row per world/dimension, recording what the plugin believes about it.
-- Kept so that an administrator can see which worlds have been analysed and with which ore
-- distributions, rather than having to infer it.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS world_analysis (
    world_key      VARCHAR(128) NOT NULL,
    analysed_at    BIGINT       NOT NULL,
    ore_ids        TEXT,
    notes          TEXT,
    CONSTRAINT pk_world_analysis PRIMARY KEY (world_key)
);


-- -------------------------------------------------------------------------------------
-- plugin_metadata — small key/value store for plugin-level state (ban-wave scheduling,
-- calibration counters, schema bookkeeping beyond schema_migrations).
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_metadata (
    meta_key   VARCHAR(128) NOT NULL,
    meta_value TEXT,
    updated_at BIGINT       NOT NULL,
    CONSTRAINT pk_plugin_metadata PRIMARY KEY (meta_key)
);


-- -------------------------------------------------------------------------------------
-- schema_migrations — the applied-migration ledger.
-- Never dropped, never rewritten: it is the only record of how the database reached its current
-- shape, and migrations are forward-only and additive.
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_migrations (
    version    INTEGER     NOT NULL,
    name       VARCHAR(128) NOT NULL,
    applied_at BIGINT      NOT NULL,
    CONSTRAINT pk_schema_migrations PRIMARY KEY (version)
);
