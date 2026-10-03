# Configuration

XRay AntiCheat is configured by four YAML files shipped in `xray-spigot/src/main/resources/`,
plus `plugin.yml` which declares the command and permissions:

| File | Read by | Purpose |
| --- | --- | --- |
| `config.yml` | `ConfigLoader` → `PluginSettings` | analysis, evidence, suspicion policy, ore models, tracking, performance, retention, debug |
| `database.yml` | `ConfigLoader.loadDatabase` → `DatabaseConfig` | storage engine and pool |
| `messages.yml` | `MessageService` | all user-facing text |
| `gui.yml` | `GuiManager` | the moderator menus |
| `plugin.yml` | the server | command registration and the permission tree |

Every value described below has a real default in the code, stated as "default". Where a key is
**parsed but not yet honoured**, or **not read at all**, that is called out explicitly.

## How the loader behaves on a bad value

`ConfigLoader` validates each section independently. If a section fails validation it substitutes
the built-in default, records a warning naming the offending path, and carries on — so one typo
can be fixed with `/xray reload` rather than a restart. Only two conditions are **fatal** (the
plugin refuses to enable, and `onEnable` disables itself):

- no ore is enabled under `ores`; or
- an enabled ore declares no `block-keys`.

Both are fatal because they would leave the engine with no inputs while the administrator
believed detection was active. A missing `messages.yml` key does not fail anything: `MessageService`
renders it as a loud `<missing: path>` rather than an empty line.

---

## 1. `config.yml`

### `analysis`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `analysis.enabled` | `true` | Master switch for the analytical engine. `false` still records data (nothing is lost) but performs no assessment and raises no alerts. Read by `ConfigLoader`. |
| `analysis.worlds` | `["*"]` | World names to observe; `["*"]` means every world. An empty list is normalised to `["*"]`. Nether and overworld are modelled separately by the ore profiles, so listing both is safe. Non-listed worlds produce no data. |

### `analysis.exposure`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `analysis.exposure.diagonal-visibility` | `false` | Whether an opening through a corner counts as making an ore visible. Off by default: corner peeking is possible but awkward, and enabling it too eagerly classifies genuinely hidden ores as visible, **weakening detection**. Maps to `ExposurePolicy.diagonalVisibility`. |
| `analysis.exposure.recent-excavation-window-seconds` | `90` | Openings **the observing player** created within this many seconds before reaching an ore count as their own approach excavation and do **not** count as pre-existing exposure. This is what makes a strip-mined ore register as buried. Raise for slow mining through long tunnels; lower if strip-miners are credited with exposure they did not have. Converted to millis for `ExposurePolicy.recentExcavationWindowMillis`. |
| `analysis.exposure.occupied-space-radius` | `1` | How far to search for a natural vantage point with line of sight when an ore is fully enclosed. Valid `[1, 3]`; beyond 3 the question stops being meaningful and only multiplies cost. |
| `analysis.exposure.excavation-attribution-required` | `false` | When true, an opening whose origin cannot be attributed (`UNKNOWN`) is not accepted as natural exposure and the classification is reported with reduced confidence. Enable if other plugins heavily modify terrain and you prefer uncertainty to a guess. |

### `analysis.evidence`

The global statistical parameters (`EvidenceParameters`), on which the ore priors in § `ores`
sit. The mathematics is in [`MATHEMATICAL_MODEL.md`](MATHEMATICAL_MODEL.md) §4–§8.

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `analysis.evidence.prior-probability` | `0.02` | Prior probability that an arbitrary player uses ore vision, before any behaviour is seen; converted to log-odds by the loader. Low is conservative. Raising it makes every player start more suspicious. **Consequential.** |
| `analysis.evidence.half-life-hours` | `168.0` | Half-life of an observation's evidentiary weight (`w(t)=2^(-t/halfLife)`). One week. Shorter = older behaviour fades faster. **Consequential.** |
| `analysis.evidence.sample-size-shrink-k` | `5.0` | `k` in `n/(n+k)`. Raises the sample needed before a signal carries weight. Higher = more conservative. **Consequential.** |
| `analysis.evidence.confidence-sample-scale` | `20.0` | `n0` in the sample-adequacy term `1-exp(-n/n0)`. Larger = confidence climbs more slowly with observations. |
| `analysis.evidence.confidence-group-scale` | `2.0` | `g0` in the independence term `1-exp(-g/g0)`. Larger = more independent families needed for high confidence. |
| `analysis.evidence.minimum-sample-size` | `10` | Hard gate: below this the verdict is `INSUFFICIENT` no matter how extreme the numbers. Raising costs detection speed, gains false-positive control. **Consequential.** |
| `analysis.evidence.minimum-independent-groups` | `2` | Hard gate: minimum independent signal families. This is the anti-evasion rule — one signal, however strong, is evadable, so the system refuses to act on a single family. Values above 2 are much more conservative and slower. **Consequential.** |
| `analysis.evidence.lookback-distance-blocks` | `8.0` | How far from an ore the player's heading is sampled for the targeting signal. Far enough that the ore is still out of sight; close enough that the heading is about this ore. |
| `analysis.evidence.legitimate-hidden-fraction` | `0.55` | Baseline proportion of a legitimate player's discoveries that are buried rather than cave-exposed; world-dependent. Tune **up** for mostly solid stone, **down** for cave-rich worlds. **Consequential.** |
| `analysis.evidence.ore-informed-hidden-fraction` | `0.95` | The corresponding proportion for an ore-vision user. Must lie strictly between `legitimate-hidden-fraction` and 1. |

### `analysis.history`

How far back an assessment reaches into the stored record. With this off, the engine sees only the
current session, so every restart and every idle prune begins the evidence base again from zero — a
cheater is measured against a few hours, and a ban wave cannot be justified by conduct that has actually
lasted months. With it on, the stored discoveries and mining events are read back and folded into each
assessment, so a player's page describes their whole recorded time.

The reads happen on the analysis worker, never on the server thread, and a hydrated history is cached for
`refresh-minutes`.

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `analysis.history.enabled` | `true` | Whether stored history is merged in. `false` restores session-only analysis. |
| `analysis.history.lookback-days` | `90` | How far back to read; must be `≥ 1`. Matching the discovery retention is what makes a season of play visible. Should not exceed `retention.mining-events-days`, or the trajectory history is thinner than requested; the loader warns when it does. |
| `analysis.history.max-discoveries` | `2000` | Per-player cap on stored discoveries read, bounding one assessment's cost; must be `≥ 1`. Hitting it marks the history truncated (it becomes a floor, not the whole past). |
| `analysis.history.max-mining-events` | `20000` | Per-player cap on stored mining events read, which is what bounds rebuilding the historical trajectory; must be `≥ 1`. |
| `analysis.history.refresh-minutes` | `15` | How long a hydrated history is reused before it is read again; must be `≥ 1`. Raise to trade freshness for fewer database round trips. |

**What is reconstructed exactly.** Discoveries are stored in full, including the two alignment angles the
targeting model consults, and so are the per-discovery effort figures, so the waiting-time model is
rebuilt rather than approximated. Blocks mined uses the same definition as the live count. Distance
travelled is a **lower bound** — only the distance carried on each discovery is stored, so travel that
produced no discovery is invisible (no component reads the window total, so this cannot inflate
suspicion). The trajectory is rebuilt from **mined block positions**, so it is the shape of the
excavation rather than of the player's walking; turn density is correspondingly coarser than over the
live movement path, and the live path is concatenated with it so recent behaviour keeps full fidelity.

**The seam.** Merging produces one boundary between the last stored discovery and the first of the
current session, and its true length is unknowable — the server may have been down for a week. That
discovery contributes no waiting-time interval, rather than one built from the session's effort alone,
which would understate a legitimate interval and push the verdict toward suspicion.

### `suspicion`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `suspicion.enabled` | `true` | Suspicion-layer master switch (read by `ConfigLoader` into `PluginSettings.suspicionEnabled`). `false` still records and stores every assessment, but raises no alert and takes no action — "do not act on this", not "delete the history". |
| `suspicion.enforcement-mode` | `BAN_WAVE` | How enforcement is executed: `ALERT_ONLY` never acts on the player; `IMMEDIATE` acts as soon as certainty is reached; `BAN_WAVE` holds candidates and enforces in batches. A mode can only ever **downgrade** an action. **The most consequential safety setting.** `BAN_WAVE` is the default because it is the only mode in which candidates are ever held — under `ALERT_ONLY` a player's record never accrues towards enforcement however long they play. It does not by itself ban anyone: `ban-wave.automatic-ban` is still `false`, so a wave produces a list a moderator approves. |
| `suspicion.minimum-confidence-for-alert` | `0.5` | Confidence floor below which nothing is even reported. |
| `suspicion.minimum-confidence-for-ban` | `0.75` | Confidence floor for irreversible action; must be ≥ the alert floor (validated). |
| `suspicion.minimum-strength-for-alert` | `MODERATE` | Evidence band required before a moderator is told. |
| `suspicion.minimum-strength-for-flag` | `STRONG` | Band required to record a formal flag. |
| `suspicion.minimum-strength-for-kick` | `STRONG` | Band required to disconnect a player. |
| `suspicion.minimum-strength-for-ban` | `VERY_STRONG` | Band required to remove a player; validated not to be weaker than the alert band. |
| _removed_ | — | A per-action independent-signal floor was listed here but is redundant: `EvidenceStrength.fromLogOdds` already returns `INSUFFICIENT` whenever the contributing signal families are fewer than `analysis.evidence.minimum-independent-groups`, so no stronger band — and therefore no enforcement — can be reached below that count. The key has been deleted rather than left looking like a control. |

Valid strength values, weakest to strongest: `INSUFFICIENT`, `WEAK`, `MODERATE`, `STRONG`,
`VERY_STRONG`.

### `ban-wave`

Deferred, batched enforcement (see [`STATISTICAL_MODEL.md`](STATISTICAL_MODEL.md) §9).

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `ban-wave.enabled` | `true` | Whether batched enforcement is used at all. |
| `ban-wave.interval-minutes` | `1440` (24 h) | Minimum time between waves. |
| `ban-wave.candidate-ttl-hours` | `336` (14 d) | How long a candidate stays eligible before its evidence is stale and it is dropped. |
| `ban-wave.minimum-strength` | `STRONG` | Band a candidate must have reached. |
| `ban-wave.minimum-confidence` | `0.85` | Confidence floor per candidate. |
| `ban-wave.minimum-independent-signals` | `2` | Independent families required per candidate. |
| `ban-wave.minimum-candidates` | `2` | Eligible candidates required before a wave is worth running. |
| `ban-wave.automatic-ban` | `false` | `false` = propose a list for approval; `true` = an automatic wave runs on a timer and bans when eligible. |

### `ores.<ore-id>`

Each ore is its own model because their generation genuinely differs. Both the stone and deepslate
variants of an ore should be listed; `MapOreCatalog` merges them into a single vein during
reconstruction. The section key is the ore id; a key matching a built-in profile (`diamond`,
`emerald`, `ancient_debris`) inherits that profile for anything left unset, while a genuinely new
ore must specify its own numbers (an invalid new ore is disabled with a warning rather than
guessed at).

| Key | Default (built-in diamond) | What it does / effect of change |
| --- | --- | --- |
| `enabled` | `true` | Whether this ore is analysed at all. A disabled ore is excluded from the block mapping, so its blocks never enter the statistics. |
| `display-name` | `"Diamond"` | Name used in reports and the GUI. Cosmetic. |
| `block-keys` | `minecraft:diamond_ore`, `minecraft:deepslate_diamond_ore` | Namespaced material keys mapped to this ore. Cannot be empty for an enabled ore (fatal). |
| `hidden-discovery-rate-per-1000-blocks` | `1.5` | **The single most consequential number in the file.** Buried veins a legitimate miner finds per 1000 blocks moved. A prior, not a measurement. Must be `> 0`. |
| `ore-informed-rate-multiplier` | `6.0` | How many times more buried veins an ore-vision user is modelled as finding per block moved. Must be `> 1` (a value of 1 would make the hypotheses indistinguishable). **Consequential.** |
| `min-y`, `max-y` | `-64`, `16` | The ore's generation band. Must have `min-y ≤ max-y`. Not currently consumed by the evidence components (informational for now). |
| `typical-vein-size` | `4` | Expected vein size; must be `≥ 1`. Not currently consumed (informational). |
| `expected-distance-between-discoveries` | `400.0` | Expected spacing. Not currently consumed (informational). |
| `alignment-threshold-degrees` | `30.0` | Angular tolerance within which an approach counts as "aimed at the ore". Must lie in `(0, 90)`. |
| `legitimate-move-alignment-probability` | `0.5` | Probability a legitimate player's **direction of travel** is within tolerance. Deliberately high, because a strip-miner mines what is ahead; rewarding it strongly would flag strip-miners. |
| `informed-move-alignment-probability` | `0.8` | The ore-vision counterpart; must exceed the legitimate value. |
| `legitimate-look-alignment-probability` | `0.067` | Probability a legitimate player's **camera** points within tolerance. `0.067` is not a guess: it is the exact fraction of directions inside a 30° cone, `(1 − cos 30°)/2`. **The sharper targeting signal.** |
| `informed-look-alignment-probability` | `0.5` | The ore-vision counterpart; must exceed the legitimate value. |
| `evidence-weight` | `1.0` | Relative weight of this ore's evidence in the combined total; must lie in `(0, 1]`. |

The shipped file also contains a disabled `nether_gold` entry as a worked example of adding an ore.

### `tracking`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `tracking.movement-sample-distance` | `0.5` | Minimum movement before a new path point is stored; must be `> 0`. Biggest lever on per-event collection cost. Distance is still accumulated below it. |
| `tracking.path-buffer-size` | `512` | Path points retained per player; must be `≥ 2`. |
| `tracking.mining-buffer-size` | `2048` | Recent block breaks retained per player; must be `≥ 1`. |
| `tracking.discovery-history-size` | `200` | Recent discoveries retained per player; must be `≥ 1`. |
| `tracking.analysis-interval-minutes` | `5` | Longest an active player goes without an assessment; must be `≥ 1`. |
| `tracking.session-idle-timeout-minutes` | `30` | Idle time before a session is pruned; must be `≥ 1`. |
| _removed_ | — | A departing player's session is always finalised and removed, because no further events can arrive for them and the buffers would be pure memory waste. The key was deleted rather than left implying a choice that does not exist. |

### `performance`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `performance.analysis-threads` | `4` | Worker-pool size for the fixed-thread mode; must be `≥ 1`. Ignored when virtual threads are on. |
| `performance.use-virtual-threads` | `true` | Use virtual threads instead of a fixed platform-thread pool. |
| `performance.persistence-batch-size` | `500` | Rows per JDBC batch; must be `≥ 1`. |
| `performance.flush-interval-seconds` | `10` | How often queued observations are flushed; must be `≥ 1`. |
| `performance.max-vein-size` | `64` | Upper bound on a reconstructed vein; must lie in `[1, 1024]`. Caps the per-ore-break world scan. |
| `performance.ledger-max-entries` | `2,000,000` | Maximum in-memory excavation-ledger entries; must be `≥ 1`. Largest single memory consumer. |
| `performance.ledger-retention-hours` | `72` | Hours of excavation provenance kept in memory; must be `≥ 1`. |

Note: the `PendingPersistence` queue bound (1,000,000) and the ledger pending-write valve
(5,000,000) are **hard-coded**, not configuration keys. See [`PERFORMANCE.md`](PERFORMANCE.md) §4–§5.

### `retention`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `retention.mining-events-days` | `30` | Retention for the largest table (per-block breaks); must be `≥ 1`. The default is set to match `analysis.history.lookback-days`, because this table is what the historical trajectory is rebuilt from: shortening it below the lookback silently caps the tunnel-geometry signal, and the loader warns when the two disagree. |
| `retention.ore-discoveries-days` | `90` | Retention for per-vein discoveries. |
| `retention.suspicion-snapshots-days` | `180` | Retention for assessments (evidence rows swept with them). |
| `retention.world-modifications-days` | `30` | Retention for the persisted excavation ledger. |
| _removed_ | — | Ban-wave records are retained indefinitely by design: they are the enforcement audit trail and a wave runs at most daily, so the table holds a few hundred rows a year. The key was deleted rather than left suggesting the records expire. |
| `retention.enabled` | `true` | Whether the scheduled prune runs at all. |
| `retention.prune-hour` | `4` | Hour (0–23, server local) at which the prune acts; must lie in `[0, 23]`. The prune task runs hourly and acts only during this hour. |

### `debug`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `debug.enabled` | `false` | Verbose internal logging master flag. `/xray debug` toggles it at runtime. |
| `debug.log-analysis` | `false` | Log the full evidence explanation for every assessment. Also implies `enabled`. Useful when calibrating priors; costly in production. |
| _removed_ | — | `debug.log-database` and `debug.log-paths` were parsed but wired to nothing. They have been deleted rather than left as switches an administrator could flip with no effect. |

---

### The administration panel (`web`)

The panel is off by default. Every key falls back to a safe default, so an existing `config.yml` that
predates this section yields a disabled panel rather than an unexpected HTTP server.

| Key | Default | Meaning |
| --- | --- | --- |
| `web.enabled` | `false` | Whether the panel starts at all. |
| `web.bind-address` | `127.0.0.1` | Interface to bind. Anything non-loopback requires `allow-non-loopback`. |
| `web.port` | `8099` | TCP port. |
| `web.username` | `admin` | The single administrator account. |
| `web.password-hash` | `''` | A PBKDF2-SHA256 hash. **Leave empty** and a strong password is generated, printed once, and only its hash stored. `password-hash` is never a plaintext password and the loader will not accept one: a value that is not in the stored `pbkdf2-sha256$...` form simply never verifies. |
| `web.session-minutes` | `60` | Idle session lifetime. There is also a fixed 12-hour absolute cap. |
| `web.max-failed-logins` | `5` | Failures from one address before lockout. |
| `web.lockout-minutes` | `15` | Lockout duration. |
| `web.page-size` | `25` | Rows per page in the listings (1-500). |
| `web.allow-non-loopback` | `false` | Must be true before a non-loopback `bind-address` is accepted. |
| `web.read-only` | `false` | When true, moderation actions are refused and the panel is inspection-only. |
| `web.behind-proxy` | `false` | Trust `X-Forwarded-For`. Only safe behind a proxy you control. |

If any value is unusable - an out-of-range port, a missing password hash, a non-loopback address without
the flag - the panel **refuses to start** and logs each problem. It never starts in a degraded state,
because every one of those cases would mean exposing a console the operator did not intend. See
`ADMIN_GUIDE.md` section 8.

## 2. `database.yml`

| Key | Default | What it does / effect of change |
| --- | --- | --- |
| `storage.type` | `sqlite` | `sqlite` \| `mariadb` (or `mysql`) \| `postgresql` (or `postgres`). An unsupported value warns and falls back to embedded SQLite. |
| `storage.sqlite.file` | `plugins/XRayAntiCheat/xray.db` | SQLite database path; relative paths resolve against the server working directory. |
| _removed_ | — | Write-ahead logging **is** enabled, but it is not configurable: `HikariConnectionProvider` supplies `journal_mode=WAL` and `busy_timeout=5000` as connection properties for every SQLite connection, because the server thread must never be blocked by a background write. Verified by reading `PRAGMA journal_mode` back from a live connection. See [`DATABASE.md`](DATABASE.md) §6. |
| `storage.mariadb.host` | `localhost` | MariaDB host. |
| `storage.mariadb.port` | `3306` | MariaDB port. |
| `storage.mariadb.database` | `xray_anticheat` | MariaDB database name. |
| `storage.mariadb.username` | `xray` | MariaDB user. |
| `storage.mariadb.password` | `""` | Password in the file; prefer the environment variable. |
| `storage.mariadb.password-environment-variable` | `XRAY_DB_PASSWORD` | Environment variable that takes precedence over the file password when set. |
| `storage.mariadb.connection-parameters` | `useServerPrepStmts=true&rewriteBatchedStatements=true&…` | Appended to the JDBC URL after `?`. |
| `storage.postgresql.host` | `localhost` | PostgreSQL host. |
| `storage.postgresql.port` | `5432` | PostgreSQL port. |
| `storage.postgresql.database` | `xray_anticheat` | PostgreSQL database name. |
| `storage.postgresql.username` | `xray` | PostgreSQL user. |
| `storage.postgresql.password` | `""` | Password in the file; prefer the environment variable. |
| `storage.postgresql.password-environment-variable` | `XRAY_DB_PASSWORD` | As above. |
| `storage.postgresql.connection-parameters` | `reWriteBatchedInserts=true` | Appended to the JDBC URL. |
| `storage.pool.maximum-pool-size` | `10` | Largest number of pooled connections (SQLite is capped at 4 regardless). |
| `storage.pool.minimum-idle` | `2` | Connections kept warm (SQLite capped at 1). |
| `storage.pool.connection-timeout-millis` | `10000` | How long a caller waits for a connection before failing; `DatabaseConfig` requires at least 250. |
| `storage.pool.batch-size` | `500` | Rows per JDBC batch. |
| `storage.migrations.run-on-startup` | `true` | Apply pending schema migrations at startup. Disable only if you apply them with an external tool. |
| `storage.fail-safe.continue-without-database` | `true` | An unreachable database does not prevent the plugin enabling; it runs with reduced function. `false` disables the plugin instead. |
| `storage.fail-safe.retry-interval-seconds` | `30` | Seconds between reconnection attempts; `ConfigLoader` floors this at 5. |

---

## 3. `messages.yml`

Read by `MessageService`. Every value is **cosmetic** — changing it cannot change the analysis,
only what a human reads. A missing key renders as `<missing: path>` rather than being silent.
Chat messages use the section sign (`§`) and accept `&#rrggbb` hex. The console reporting and the
interface additionally accept `&`-codes, which the plugin translates before sending; an ampersand
followed by a colour character is a code, so text like `R&D` loses its ampersand exactly as it would in
any ampersand-based colour system.

Placeholders documented in the file header: `%player%`, `%uuid%`, `%world%`, `%ore%`, `%score%`,
`%confidence%`, `%strength%`, `%evidence%`, `%samples%`, `%signals%`, `%decibans%`, `%time%`,
`%count%`, `%moderator%`, `%reason%`, `%note%`, `%prefix%`, plus the per-context ones below.

| Section | Keys | Notes |
| --- | --- | --- |
| `prefix` | — | Message prefix (`%prefix%`). |
| `lifecycle` | `banner` (list), `startup` (list), `ready`, `shutdown`, `reload-success`, `reload-failed`, `disabled-by-config`, `database-unavailable`, `database-recovered`, `schema-migrated` | Everything the plugin prints on the **server console**, including the startup banner. Set `banner` to `[]` for no banner. The art is deliberately plain ASCII: the block-drawing characters fancier banners use are the ones older console fonts cannot render, and a banner that shows as mojibake is worse than none. |
| `alerts` | `header`, `body` (list), `footer`, `hint`, `suppressed` | Staff alert format; `body` uses `%top-signal%`, `suppressed` uses `%count%`/`%time%`. |
| `commands` | `no-permission`, `player-not-found`, `player-not-online`, `invalid-arguments`, `unknown-subcommand`, `self-target`, `world-not-tracked`, `help` (list) | Command feedback. `no-permission` uses `%permission%`. |
| `status` | `header`, `lines` (list) | `/xray status`; uses `%analysis-state%`, `%suspicion-state%`, `%pool%`, `%thread-type%`, `%tracked%`, `%queued%`, `%ledger%`, `%candidates%`, `%last-wave%`, `%assessments%`, `%alerts%`. |
| `stats` | `header`, `no-data`, `per-ore`, `totals` (list) | Per-player mining statistics (live session). |
| `evidence` | `header`, `verdict`, `no-evidence`, `explanation-header`, `contribution`, `contribution-positive`, `contribution-negative` | Evidence report; `contribution` uses `%direction%`, `%component%`, `%explanation%`. |
| `history` | `header`, `no-history`, `entry` | Assessment history (stored). |
| `actions` | `inspect-opened`, `teleported`, `vanish-unavailable`, `external-plugin-required`, `spectating`, `frozen`, `unfrozen`, `flagged`, `alerts-rearmed`, `note-instruction`, `note-added`, `kick-manual`, `ban-manual`, `target-kicked`, `target-banned`, `inspect-note` | Moderator action feedback. `note-added` is sent by `/xray note`, which can collect the text; `note-instruction` by the interface, which cannot. `vanish-unavailable` and `external-plugin-required` state that an action needs an external plugin rather than reporting it done. `alerts-rearmed` confirms the alert throttle was cleared for a player. |
| `ban-wave` | `planning`, `none-eligible`, `planned`, `approved`, `executed`, `candidate-list-header`, `candidate-line`, `automatic`, `manual-required` | Wave messages. |
| `enforcement` | `ban-reason`, `ban-reason-append`, `kick-reason`, `broadcast` | Reasons and the broadcast; `ban-reason-append` uses `%snapshot-id%`. |
| `errors` | `config-invalid`, `config-fatal`, `database`, `analysis`, `persistence-dropped`, `permission`, `internal` | Error messages. |

---

## 4. `gui.yml`

Read by `GuiManager`. Menu layout, item materials, names, lore and sounds are **cosmetic**. Actions are
named and executed by `GuiManager.dispatch`. Every action below has a real effect; an action name this
build does not recognise is logged and does nothing, rather than failing silently.

| Section | Keys | Notes |
| --- | --- | --- |
| `common` | `filler` | The item that pads empty slots. Navigation is defined per menu, because each menu returns somewhere different. |
| `player-list` | `title`, `size`, `player-item`, `empty`, `navigation` | Tracked players, sorted by suspicion (live cache). |
| `player-detail` | `title`, `size`, `items` (list of slots) | One player, with moderation actions. |
| `statistics` | `title`, `size`, `header-slot`, `back-slot`, `ore-item`, `ore-materials`, `back`, `empty` | Per-ore breakdown of discoveries. |
| `confirm` | `title`, `size`, `confirm-slot`, `cancel-slot`, `confirm`, `cancel`, `prompt` | Confirmation before an action. |
| `settings` | `explain-missing-permission`, `open-sound`, `click-sound`, `fill-empty-slots` | Interface behaviour. |

### 4.1 Pagination

`player-list.navigation` defines the previous / page-info / next row. Entries are laid out from slot 0 up
to the lowest of the three configured slots, so the navigation row can never overwrite a player head and
the number of heads per page follows from the configured slots rather than a fixed number. Remove the
section to disable paging and use every slot. The previous and next items are only placed when a page
exists in that direction, so the buttons never lead to an empty window.

### 4.2 Actions

| Action | Behaviour |
| --- | --- |
| `OPEN:<menu-id>` | opens another menu: `player-list`, `player-detail` or `statistics` |
| `INSPECT` | prints the stored explanation to chat |
| `FLAG` | records a `FLAG` audit entry |
| `KICK` / `BAN` | opens a confirmation menu, then acts |
| `CLEAR` | clears the alert throttle for the player, so their next alert arrives immediately |
| `ADD_NOTE` | points the moderator at `/xray note <player> <text>`, which can collect the text |
| `TELEPORT` | teleports the moderator to the player |
| `TELEPORT_VANISH` | teleports, then reports that vanish needs an external plugin |
| `SPECTATE` / `FREEZE` | report that an external plugin is required, and change nothing |
| `CLOSE` | closes the menu |

Permissions are re-checked when an item is clicked, not only when the menu is drawn, so a menu left open
across a permission change cannot be used to run the action. Clicks and drags involving a menu are always
cancelled: items can neither be taken out of a menu nor dragged into one.

### 4.3 `config-version`

Each shipped file declares `config-version`. If a file declares a version higher than the running build
understands, the plugin logs a warning at startup and on `/xray reload`, because settings added by a newer
release would otherwise be ignored in silence. A missing key is treated as current.

Per [`PRIVACY.md`](PRIVACY.md), opening the interface generates no evidence.

---

## 5. `plugin.yml`

Declares the `xray` command (aliases `xrayac`, `ac`; base permission `xray.inspect`) with the
subcommands `help`, `status`, `inspect`, `stats`, `evidence`, `history`, `gui`, `banwave`,
`note`, `reload`, `debug`. Permissions (all default `op`, with `xray.*` granting the rest):
`xray.admin`, `xray.alerts`, `xray.inspect`, `xray.teleport`, `xray.freeze`, `xray.kick`,
`xray.ban`, `xray.banwave`, `xray.reload`, `xray.debug`. Each subcommand checks its own
permission (`XRayCommand.requirePermission`), so the tree is graded rather than gated at one
point.

---

## 6. Which keys are consequential, and which are cosmetic

**Consequential** — these change who gets assessed and what happens to them:

- `ores.*.hidden-discovery-rate-per-1000-blocks` and `ores.*.ore-informed-rate-multiplier` — the
  two numbers the whole rate model turns on.
- `ores.*.legitimate-*-alignment-probability` / `informed-*-alignment-probability` and
  `alignment-threshold-degrees` — the targeting model.
- `ores.*.evidence-weight`, `ores.*.enabled`, `ores.*.block-keys`.
- `suspicion.enabled`, `suspicion.enforcement-mode`.
- `suspicion.minimum-strength-*` and `suspicion.minimum-confidence-*` — the decision gates.
- `analysis.evidence.minimum-sample-size`, `minimum-independent-groups`, `prior-probability`,
  `half-life-hours`, `sample-size-shrink-k`, `legitimate-hidden-fraction`,
  `ore-informed-hidden-fraction`.
- `ban-wave.*` thresholds and `automatic-ban`.
- `analysis.exposure.*` — how visibility is judged, hence which discoveries count as hidden.
- `analysis.worlds`, `analysis.enabled`.

**Operational (change cost and storage, not verdicts):** everything under `tracking`,
`performance`, `retention`, `storage.pool`, `storage.migrations`, `storage.fail-safe`.

**Cosmetic (text and layout only):** all of `messages.yml`, all of `gui.yml`, all `display-name`
values, the `settings.*` sounds.

**Parsed but inert (no effect yet):** the per-ore `min-y`, `max-y`, `typical-vein-size` and
`expected-distance-between-discoveries`. These are carried on `OreProfile` and validated, but no
evidence component reads them yet: the discovery-rate and waiting-time models use
`hidden-discovery-rate-per-1000-blocks` and `ore-informed-rate-multiplier` instead, and vein size is
measured from the reconstruction rather than compared against `typical-vein-size`. They are retained
on the profile because they describe the ore and are needed by any future depth- or size-aware
component, but an administrator should not expect changing them to alter a verdict today.

Every other key in this document is read by the plugin and has the effect described.

---

## 7. Tuning your ore priors

The rate priors are beliefs, not measurements. The model tolerates a prior wrong by a factor of
two — it shifts the accumulated evidence slightly rather than flipping a verdict — but the order
of magnitude and the relative ordering between ores must be roughly right. A short, safe
procedure:

1. **Consider running in `ALERT_ONLY` while you calibrate.** Setting `suspicion.enforcement-mode` to
   `ALERT_ONLY` means nobody is acted on while the system gathers data. It is not the shipped default —
   under `ALERT_ONLY` no candidate is ever held, so a player's record does not accrue towards a wave —
   but it is the right setting while you are checking that the evidence matches what you see in game.
   With the shipped `BAN_WAVE` default nothing is enforced unreviewed either, because
   `ban-wave.automatic-ban` is `false`.
2. **Measure your players' actual yields.** Query `ore_discoveries`: count hidden discoveries per
   player per world and divide by `blocks_mined` (`mining_events`), then average over known-legit
   players. Set `hidden-discovery-rate-per-1000-blocks` to roughly that observed value per ore,
   not to the shipped default if your world differs.
3. **Set the multiplier from the evasion you mean to catch.** The multiplier is what an
   ore-vision user's rate is believed to be, relative to legitimate. 4–6 is the shipped range. A
   larger multiplier moves the crossover rate (see [`MATHEMATICAL_MODEL.md`](MATHEMATICAL_MODEL.md)
   §3.1) and makes the model stricter.
4. **Calibrate the mix baseline.** `legitimate-hidden-fraction` should reflect how much of your
   players' ore is cave-exposed. Cave-rich world → lower it; mostly solid stone → raise it.
5. **Watch the band distribution, not individual alerts.** With `debug.log-analysis: true`, run
   for a few days and check that ordinary productive players produce strongly negative evidence
   and that `STRONG`/`VERY_STRONG` are rare and populated by players who look wrong by hand. If
   legitimate players reach actionable bands, raise `minimum-sample-size`,
   `minimum-independent-groups`, or `sample-size-shrink-k` before touching enforcement.
6. **Only then consider raising the mode.** Move to `BAN_WAVE` (with `automatic-ban: false`)
   before `IMMEDIATE`, and keep the confidence floors where they are.

Do not edit priors to "catch more"; edit them to match your world. The false-positive controls are
in the gates (§ `analysis.evidence`), and those are the honest knobs for sensitivity.

See also: [`ARCHITECTURE.md`](ARCHITECTURE.md) for where each setting is consumed,
[`PERFORMANCE.md`](PERFORMANCE.md) for the cost of each, [`DATABASE.md`](DATABASE.md) for the
storage and retention, and [`PRIVACY.md`](PRIVACY.md) for what the data keys mean for players.
