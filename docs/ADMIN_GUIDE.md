# Administrator Guide

This guide is for the person who installs, configures and maintains XRay AntiCheat on a Paper
server. It covers setup, the configuration keys that actually change outcomes, storage, permissions,
evidence interpretation, tuning, backups, migrations and troubleshooting. The day-to-day
investigation workflow lives in `docs/MODERATOR_GUIDE.md`; the mathematics lives in
`docs/MATHEMATICAL_MODEL.md` and its interpretation in `docs/STATISTICAL_MODEL.md`.

A note on posture before anything else: **the plugin ships in `BAN_WAVE` mode, which gathers candidates
and plans waves but bans nobody on its own** — `ban-wave.automatic-ban` is `false`, so every wave waits
for a moderator to approve it. Treat your first weeks with it as calibration: watch what it produces,
compare it against what you know about your players, and only then decide whether a wave should be
allowed to run automatically, or whether to move to `IMMEDIATE`.

---

## 1. Installation

Requirements:

| Component | Version |
| --- | --- |
| Server | Spigot, Minecraft 26.2 (`spigot-api 26.2-R0.1-SNAPSHOT`) |
| Java | Java 25 (class files `major 69`; compiled and tested with Temurin 27+35 via `--release 25`) |
| Folia | Not supported |

Steps:

1. Build the plugin (`mvn clean package`) or obtain a release jar. The jar is
   `xray-spigot/target/xray-anticheat-1.0.0.jar` (about 330 KB; it contains only the plugin's own
   code and configuration).
2. Copy it to `plugins/`.
3. Start the server. **The first start needs network access to Maven Central** — see below.
4. Confirm the plugin is healthy with `/xray status`.
5. Optionally enable the administration web panel - see section 8.

On first start the plugin creates `plugins/XRayAntiCheat/`, writes the four configuration files,
creates and migrates its database (SQLite by default at `plugins/XRayAntiCheat/xray.db`), and logs a
startup banner showing the storage dialect, schema version, the ore types it is analysing, the
enforcement mode and the worker count.

### Servers without internet access

The plugin does not bundle its database drivers. On first start the server downloads the four
libraries listed under `libraries:` in the plugin's `plugin.yml` — HikariCP, sqlite-jdbc, MariaDB
Connector/J and the PostgreSQL JDBC driver — from Maven Central, caches them in the server's
`libraries/` directory and adds them to the plugin's classpath. Paper caches them, so this happens
once, and it is the trade that takes the plugin download from 14 MB to 330 KB.

If your server cannot reach Maven Central, you have three options, in order of preference:

1. **Point the server at an internal mirror.** Start the server with
   `-D(no Spigot equivalent of a repository-override property)=https://your.maven.mirror/repository/maven-public/` and let it
   fetch from there. This is the cleanest option if you already run a repository manager.
2. **Pre-seed the cache.** On a machine that does have access, start the server once with the plugin
   installed, then copy the server's `libraries/` directory to the offline server. The downloads are
   named `<artifact>-<version>.jar` and are self-contained.
3. **Fetch the jars by hand.** Download those four artifacts (plus their transitive dependencies)
   from Maven Central or your mirror and place them in the server's `libraries/` directory.

Note that a server in this situation does **not** fail quietly. The plugin's storage initialisation
catch handles `RuntimeException`, not `Error`, so a missing library class
(`NoClassDefFoundError`) propagates out of `onEnable`: Paper logs the failure and disables the
plugin. If the plugin is not in `/plugins` after startup, check the console for a library download
failure before assuming the jar is corrupt.

---

## 2. Configuration files

Four files under `plugins/XRayAntiCheat/`. Every value in them is read and has a real effect; none
is a placeholder. They are commented in place, and those comments are the primary reference. Apply
changes with `/xray reload`, which is atomic — the whole validated settings object is swapped at
once and in-memory observations are preserved. A reload that fails leaves the previous configuration
in effect and reports the error.

### 2.1 `config.yml` — the model

The most consequential keys, roughly in order of impact:

| Key | Default | Why it matters |
| --- | --- | --- |
| `ores.*.hidden-discovery-rate-per-1000-blocks` | diamond 1.5, emerald 0.35, ancient debris 2.5 | The baseline buried-vein yield for a legitimate miner per 1000 blocks of rock moved. **This is the single most consequential number in the file.** |
| `ores.*.ore-informed-rate-multiplier` | diamond 6.0, emerald 6.0, ancient debris 4.0 | How many times more buried veins an ore-vision user is modelled as finding. Must be `> 1`; 1 would make the hypotheses indistinguishable. |
| `analysis.evidence.legitimate-hidden-fraction` | 0.55 | The share of a legitimate player's discoveries that are buried rather than cave-exposed. World-dependent: raise for mostly-solid stone, lower for cave-rich worlds. |
| `analysis.evidence.ore-informed-hidden-fraction` | 0.95 | The corresponding share for an ore-vision user. Must lie strictly between `legitimate-hidden-fraction` and 1. |
| `analysis.evidence.prior-probability` | 0.02 | Prior belief that an arbitrary player is cheating. Deliberately low so real evidence does the work rather than a default suspicion. |
| `analysis.evidence.minimum-sample-size` | 10 | No conclusion may rest on fewer independent observations than this. Raising it costs detection speed; lowering it buys speed at the cost of false positives. |
| `analysis.evidence.minimum-independent-groups` | 2 | No conclusion may rest on fewer independent signal families. The anti-evasion rule. |
| `analysis.evidence.half-life-hours` | 168 | Evidentiary half-life of an observation. One week. |
| `analysis.evidence.sample-size-shrink-k` | 5.0 | Shrinks thin evidence: `n/(n+k)`. With k = 5, two observations contribute ~29% of nominal weight, fifty contribute 91%. |
| `suspicion.enforcement-mode` | `BAN_WAVE` | Gathers candidates and plans waves; bans nobody unless `ban-wave.automatic-ban` is turned on. `ALERT_ONLY` never acts (and holds no candidates); `IMMEDIATE` acts at once. Change only deliberately. |
| `suspicion.minimum-confidence-for-alert` / `-for-ban` | 0.5 / 0.75 | The two confidence gates. |
| `suspicion.minimum-strength-for-alert` / `-flag` / `-kick` / `-ban` | MODERATE / STRONG / STRONG / VERY_STRONG | The evidence band required for each action. |
| `ban-wave.*` | see §7 | Interval, candidate TTL, thresholds, minimum candidates and automatic execution. |
| `analysis.worlds` | `["*"]` | Worlds to analyse. Keep the nether and overworld both listed if you mine in both; their ore distributions are modelled separately, so mixing them here is safe. |
| `performance.*` | 4 threads, virtual, batch 500, flush 10 s, ledger 2,000,000 entries | Worker and I/O tuning, ledger bounds and vein cap. |
| `retention.*` | 7/90/180/30/365 days | How long each class of stored data is kept, and whether the daily prune runs. |
| `debug.*` | off | Verbose logging; never leave `enabled` on in production. |

Two keys in `analysis.exposure` deserve a mention because they shape what counts as "hidden":
`recent-excavation-window-seconds` (90) treats openings the player themselves made shortly before
reaching an ore as their own approach excavation, which is what makes a strip-mined ore register as
genuinely buried; and `diagonal-visibility` (false) decides whether peeking diagonally past a block
edge counts as making an ore visible.

### 2.2 `database.yml` — storage

See §3. The engine is selected by `storage.type: sqlite | mariadb | postgresql`. The pool defaults
to a maximum of 10 connections, which is deliberately modest — this plugin's traffic is bursty but
light, and a larger pool multiplies lock contention. `storage.fail-safe.continue-without-database`
(strongly recommended, default `true`) keeps the server running when the database is unreachable.

### 2.3 `messages.yml` — all user-facing text

Nothing user-facing is hard-coded in Java. Alerts, status output, command replies, error text and the
enforcement ban reason all live here. Placeholders such as `%player%`, `%strength%`, `%score%`,
`%confidence%`, `%decibans%`, `%samples%` and `%signals%` are substituted at send time. Colour codes
use `§` and support `&#rrggbb` hex colours.

### 2.4 `gui.yml` — the moderator interface

Every menu, its size, items, lore, permissions and the action each item runs. Actions include
`OPEN:<menu-id>`, `INSPECT`, `TELEPORT`, `TELEPORT_VANISH`, `SPECTATE`, `FLAG`, `CLEAR`, `FREEZE`,
`KICK`, `BAN`, `ADD_NOTE` and `CLOSE`. Permissions are always re-checked when an item is clicked, so
putting an action in this file never grants it to anyone.

---

## 3. Database setup

SQLite is the default and needs no setup. Move to MariaDB or PostgreSQL when you run a network of
servers that should share one record, or when the data outgrows a single file.

> **The operator creates the database; the plugin creates its tables.** With MariaDB and PostgreSQL
> the plugin assumes the database and user already exist. It only creates and migrates its own
> tables inside them.

### 3.1 SQLite

```yaml
storage:
  type: sqlite
  sqlite:
    file: "plugins/XRayAntiCheat/xray.db"
```

Relative paths resolve against the server's working directory. Write-ahead logging and a writer
busy-timeout are applied automatically to every SQLite connection, so there is nothing to configure
and nothing else to install. (You will see `xray.db-wal` and `xray.db-shm` beside the database file;
that is expected, and it is why a backup should copy all three — see the backup section below.)

### 3.2 MariaDB

On the database server:

```sql
CREATE DATABASE xray_anticheat
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'xray'@'localhost' IDENTIFIED BY 'a-strong-password';
GRANT ALL PRIVILEGES ON xray_anticheat.* TO 'xray'@'localhost';
FLUSH PRIVILEGES;
```

In `database.yml`:

```yaml
storage:
  type: mariadb
  mariadb:
    host: "localhost"
    port: 3306
    database: "xray_anticheat"
    username: "xray"
    password: ""                     # leave empty and use the environment variable instead
    password-environment-variable: "XRAY_DB_PASSWORD"
    connection-parameters: "useServerPrepStmts=true&rewriteBatchedStatements=true&useUnicode=true&characterEncoding=utf8"
```

The shipped `connection-parameters` make bulk inserts dramatically faster on MariaDB
(`useServerPrepStmts` and `rewriteBatchedStatements` together).

### 3.3 PostgreSQL

On the database server:

```sql
CREATE DATABASE xray_anticheat;
CREATE USER xray WITH ENCRYPTED PASSWORD 'a-strong-password';
GRANT ALL PRIVILEGES ON DATABASE xray_anticheat TO xray;

\c xray_anticheat
GRANT ALL ON SCHEMA public TO xray;
```

In `database.yml`:

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
```

### 3.4 Credentials from the environment

Leaving `password` empty and setting the named environment variable is the recommended way to hold a
password: it keeps the secret out of `database.yml`, and therefore out of backups and version
control. Set the variable on the server process, not in your shell, so it survives restarts — for
example via `Environment=XRAY_DB_PASSWORD=…` in a systemd unit, or the equivalent for your service
manager. The variable name is configurable per engine.

### 3.5 Pool and fail-safe

```yaml
storage:
  pool:
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout-millis: 10000
    batch-size: 500
  migrations:
    run-on-startup: true
  fail-safe:
    continue-without-database: true
    retry-interval-seconds: 30
```

If the database is unreachable, the plugin logs a clear message and continues with reduced function
rather than preventing the server from starting (§11). It retries every
`retry-interval-seconds`.

---

## 4. Permissions and who should have them

| Permission | Grants | Suggested holder |
| --- | --- | --- |
| `xray.admin` | View status and health. | Administrators, or anyone doing maintenance. |
| `xray.alerts` | Receive alerts. | All moderators and administrators. |
| `xray.inspect` | Inspect players, read reports, open the GUI, set notes. | Every moderator. This is the baseline. |
| `xray.teleport` | Teleport to a player from the interface. | Moderators trusted to observe players in world. |
| `xray.freeze` | Hold a player in place. | Moderators. |
| `xray.kick` | Kick from the interface. | Senior moderators. |
| `xray.ban` | Ban from the interface. | Only staff you would trust to remove a player by hand. |
| `xray.banwave` | Review, plan and approve ban waves. | Administrators or a designated senior moderator. |
| `xray.reload` | Reload configuration. | Administrators only. |
| `xray.debug` | Toggle debug logging. | Administrators only. |
| `xray.*` | Everything above. | Administrators only. |

All permissions default to `op`. Give `xray.inspect` and `xray.alerts` widely; keep `xray.ban`,
`xray.banwave`, `xray.reload`, `xray.debug` and `xray.*` narrow. The grading is the point: a
moderator who can read evidence is not thereby a moderator who can remove players.

---

## 5. Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/xray help` | `xray.inspect` | List the commands. |
| `/xray status` | `xray.admin` | Version, mode, storage, pool, workers, tracked players, ledger size, candidates, assessments and alerts. |
| `/xray inspect <player> [gui]` | `xray.inspect` | Full evidence report; optionally open the GUI. |
| `/xray stats <player>` | `xray.inspect` | Per-ore mining statistics (buried vs exposed). |
| `/xray evidence <player>` | `xray.inspect` | Current evidence breakdown. |
| `/xray history <player>` | `xray.inspect` | Recent stored assessments. |
| `/xray gui [player]` | `xray.inspect` | Moderator interface. |
| `/xray banwave [status\|plan\|approve]` | `xray.banwave` | Review candidates, plan or execute a wave. |
| `/xray note <player> <text>` | `xray.inspect` | Attach a note to a player's record. |
| `/xray reload` | `xray.reload` | Reload configuration. |
| `/xray debug` | `xray.debug` | Toggle debug logging. |

Aliases: `/xrayac`, `/ac`.

Use `/xray status` as your health check. It reports the enforcement mode, whether analysis and
suspicion are on, the storage dialect and schema version, the pool state, the worker count and
thread type, how many players are tracked and observations queued, the excavation ledger size, the
ban-wave candidate count and the last wave time, and today's assessment and alert counts. If the
database is down, the pool shows as unavailable.

---

## 6. Alerts

Alerts go to everyone with `xray.alerts`. They are deliberately terse — they point a moderator at the
evidence rather than replacing it:

```
---------- XRay Alert ----------
Player    Steve (5f2b…e91c)
World     world
Verdict   strong evidence (0.9821 suspicion, 0.91 confidence)
Evidence  11.5 decibans over 42 observation(s) from 3 independent signal(s)
Top signal: hidden-discovery-rate: 9.4 hidden diamond discoveries against 2.1 expected
-----------------------------------
Use /xray inspect Steve to review the full evidence.
```

An alert is raised when an assessment reaches both the configured evidence band
(`minimum-strength-for-alert`) and the configured confidence floor (`minimum-confidence-for-alert`)
**and** the engine's own minimum-sample-size and minimum-independent-groups gates have been met. If
a player generates a burst of alerts, repeats are suppressed for a period so the channel is not
flooded; the suppression count is shown on the next alert.

Alert frequency is a tuning signal, not a verdict on your players. If alerts are constant and your
staff keep finding honest players behind them, your priors or your baselines are wrong for your
world — go to §10, not to enforcement.

### 6.1 Reaching moderators who are not online

An in-game alert only reaches someone who is in the game. `alerts.discord` forwards the same
events to a channel webhook, which is how you find out about a player at three in the morning.
Full setup instructions, including creating the webhook and pinging a role, are in
[`DISCORD.md`](DISCORD.md).

```yaml
alerts:
  # Suppresses further alerts about the SAME player for this long. Repeats are counted and
  # reported on the next alert rather than dropped. 0 disables throttling.
  throttle-minutes: 5

  discord:
    enabled: false
    webhook-url: ""          # must be https://
    events: [ALERT, KICK, BAN, BAN_WAVE]
    minimum-strength: MODERATE
    minimum-confidence: 0.5
```

The Discord thresholds are the same two gates the in-game alerts use, applied independently, so the
channel can be quieter than staff chat without changing what staff see. The webhook URL is a
credential: anyone holding it can post to the channel, so keep it out of any file you commit.

### 6.2 Using your own punishment commands

If your network already runs a punishment plugin, `enforcement.commands` runs your command instead
of this plugin's built-in kick and ban, so nothing writes to the vanilla ban list behind that
system's back.

```yaml
enforcement:
  commands:
    ban: "networkban %player% 30d %reason%"
  log-commands: false
```

Whatever you configure, the player is still disconnected and this plugin still writes its own audit
row. Set `log-commands: true` while testing. The available placeholders and the full behaviour are
documented in [`DISCORD.md`](DISCORD.md#related-replacing-the-kick-and-ban-commands).

---

## 7. Ban waves and enabling automatic enforcement

The plugin ships at Stage 2 below: `BAN_WAVE` with `automatic-ban: false`. It accumulates candidates and
prepares waves for a moderator to approve, and executes nothing on its own. The full path is set out here
so you can move deliberately in either direction.

### Stage 1 — `ALERT_ONLY` (observation only)

```yaml
suspicion:
  enforcement-mode: ALERT_ONLY
```

The plugin alerts and records. Nothing touches a player. Note that in this mode **no candidate is ever
held**, so a player's record does not accrue towards a wave however long they play; use it while you are
checking that the evidence matches what you see in game (§10).

### Stage 2 — manual ban waves

```yaml
suspicion:
  enforcement-mode: BAN_WAVE
ban-wave:
  enabled: true
  automatic-ban: false
```

Now the system accumulates candidates and, when a wave is due and enough candidates qualify, prepares
a list for a human. Review it with `/xray banwave plan` (which prints the candidate summary) and
execute it with `/xray banwave approve`. Candidates must meet `minimum-strength`,
`minimum-confidence` and `minimum-independent-signals`, and there must be at least
`minimum-candidates` of them before a wave is worth running. Candidates expire after
`candidate-ttl-hours` if nothing reinforces them. The deliberate design here is that **stored
evidence is recomputed before anyone is removed**, and that a wave of one — which is just a delayed
immediate ban — is not permitted. Because every assessment reaches back over
`analysis.history.lookback-days` rather than only the current session, the evidence behind a candidate
describes a player's whole recorded time; that is what makes a delayed wave defensible rather than a
judgement on a few hours.

### Stage 3 — automatic ban waves (opt in only with care)

```yaml
ban-wave:
  automatic-ban: true
```

Now a due wave executes without approval. Only enable this after running Stage 2 for a while and
being satisfied with every wave it produced. The system is designed to never be certain (§9): it
reports evidence, not proof, and automating removal means accepting that some evidence will
eventually be wrong for your world.

`IMMEDIATE` mode is also available but is not a recommended starting point: acting the moment a
threshold is crossed removes the player while their technique still works, teaches the community the
threshold, and acts on a single moment rather than a body of evidence.

Every enforcement action — manual or automatic — is logged with the acting moderator's name where
there is one.

---

## 8. The administration web panel

The plugin can serve its own moderator console, so evidence can be reviewed from a browser instead of
in-game. It is embedded - there is nothing else to install, and it adds nothing to the plugin jar - and
it is **disabled by default**.

### Enabling it

```yaml
web:
  enabled: true
  bind-address: 127.0.0.1
  port: 8099
  username: admin
  password-hash: ''      # leave empty; a password is generated and printed once
```

Reload with `/xray reload`, or restart. With an empty `password-hash`, the plugin generates a strong
password, prints it to the console **once** and stores only its PBKDF2-SHA256 hash. Then sign in at
`http://127.0.0.1:8099`.

To set your own password, put the hash in the configuration, or use:

```
/xray webpassword <new-password>
```

which hashes it for you, writes only the hash, and restarts the panel so any existing session ends. Note
that a password given as a command argument appears in the server's command log if it logs commands -
where that matters, set it through the configuration file instead.

### Reaching it from another machine

Tunnel to it, and leave the panel bound to loopback:

```bash
ssh -L 8099:127.0.0.1:8099 you@yourserver
```

Then open `http://127.0.0.1:8099` locally.

Binding it to a real interface is supported but must be explicit: set `bind-address` and
`allow-non-loopback: true`. The panel refuses a non-loopback address until you do, on purpose. There is
no TLS, because terminating it would need either a dependency or a certificate lifecycle this feature
cannot own, and a password-protected moderation console in plain text on a public interface is a
decision that should never happen by accident.

### What it can and cannot do

It reads assessments, discoveries, statistics, the candidate list and the audit trail, and it writes
audit entries. The only changes it can make to the game are **kick, ban, unban and message** - and it
reports what actually happened, so a kick for a player who has already logged out is reported as *not
online* rather than as a success.

It cannot edit evidence. It cannot change a verdict, delete a discovery or adjust a score. Setting
`read-only: true` removes the moderation actions entirely, leaving inspection.

### Security posture, and its limits

| Control | Behaviour |
| --- | --- |
| Bind address | Loopback unless `allow-non-loopback` is set. |
| Password | PBKDF2-HMAC-SHA256, 210,000 iterations, per-password salt, constant-time comparison. |
| Sessions | Random opaque tokens in an `HttpOnly; SameSite=Strict` cookie; idle expiry plus a 12-hour cap. |
| CSRF | A session-bound token on every state-changing request. |
| Lockout | Per-address after `max-failed-logins`, for `lockout-minutes`. Keyed per address, so an attacker cannot lock out the real administrator. |
| Headers | Strict CSP (`default-src 'none'`, no inline script, no external origin), `nosniff`, `DENY` framing, `no-store`. |
| Escaping | Every interpolated value is escaped where it is rendered. |
| Reads | Bounded by `page-size`; no unbounded queries. |

Set `behind-proxy: true` **only** when a reverse proxy you control always sets `X-Forwarded-For`. If the
panel is directly reachable, that header can be forged and the per-address lockout bypassed.

Out of scope, stated plainly: anyone with local access to the machine, or who can read `config.yml`.
Both defeat a console embedded in a plugin, and no amount of code here changes that.

### If it does not start

The panel logs why, in every case. Common causes: `web.enabled` is false (it is off by default); no
`password-hash` was generated (reload rather than assuming it failed); the port is in use; storage is
unavailable, which the panel needs; or a non-loopback `bind-address` without `allow-non-loopback: true`.

The plugin itself is unaffected by any of these. The panel failing never stops detection.
## 9. The inspector GUI

`/xray gui` opens the interface; `/xray gui <player>` or `/xray inspect <player> gui` opens a
specific player. It is defined entirely by `gui.yml`.

- **Player list** — tracked players sorted by suspicion. Each head shows the verdict, score,
  confidence, decibans, signals and the buried/exposed split. When more players are tracked than fit
  on one page, a previous / page-counter / next row appears along the bottom; the arrow for a
  direction is only shown when a page exists that way.
- **Player detail** — the evidence report and the moderation actions: teleport, teleport-while-
  vanished, spectate, freeze, add note, re-arm alerts, flag, kick, ban. Kick and ban open a
  confirmation menu, and the chat prompt restates what is about to happen; the confirmation is logged
  against the moderator's name.
- **Statistics** — the per-ore buried/exposed breakdown, one row per ore type with its own icon
  (`statistics.ore-materials`), counted in **discoveries, not blocks**: a vein worked through end to
  end is still one judgement about whether the player could have seen it.

Two behaviour notes for administrators: the interface renders from in-memory state rather than the
database (a moderator wants to know what is true *now*), and permissions are re-checked on click, not
merely when the menu was drawn, so a stale open inventory or a forged client click cannot execute an
action the viewer is not entitled to. Opening the interface, spectating or teleporting **generates no
evidence** for or against the player — an inspection must never itself change a record that might
later justify a ban.

Four actions report honestly rather than pretending:

- **Teleport, vanished** teleports, then tells you that vanish needs an external plugin. You are not
  hidden.
- **Spectate** and **freeze** need an external plugin and change nothing; the menu says so instead of
  leaving you to assume the player is frozen.
- **Add note** points you at `/xray note <player> <text>`, because a menu click has nowhere to type
  the text.
- **Re-arm alerts** clears the alert throttle for that player, so their next alert arrives
  immediately instead of being folded into a later summary. It does not change their record.

---


## 10. Interpreting evidence: the four numbers

Moderators must be able to tell these four apart; conflating any two is the classic anti-cheat
mistake. They appear on every verdict line.

| Number | Question it answers | Range | Notes |
| --- | --- | --- | --- |
| **Suspicion score** | *How much does the observed behaviour favour the ore-vision hypothesis?* | 0–1 | A statement about the data. It can be large on a handful of observations, which is exactly why it is not used alone. |
| **Statistical confidence** | *How much should we trust that statement?* | 0–1 | A statement about the evidence base: how many observations, and how many independent signal families. High score with low confidence is "startling, but we have barely looked". Low score with high confidence is "we have looked carefully and found nothing". |
| **Evidence strength** | *The banded verdict* | insufficient / weak / moderate / strong / very strong | Score and confidence banded together onto the forensic scale, gated by minimum sample size and minimum independent signals. This is what the decision policy acts on, because it already encodes "enough data". |
| **Sample size** | *How many independent observations, across how many independent signals?* | counts | The raw inputs to confidence, shown so a moderator can see exactly what the judgement rests on. |

The bands sit on a logarithmic likelihood-ratio scale — a ratio of 3 is weak, 10 moderate, 100
strong, 1000 very strong. Anything below a ratio of 3, or with too few observations or signals, is
**insufficient evidence**. That label covers both "not enough data" and "the evidence points the
other way"; a cave explorer whose buried fraction argues against cheating produces strongly negative
log-odds, and calling that "weak evidence" would read as a mild accusation. Insufficient evidence is
not a suspicion.

The full interpretation — assumptions, false-positive control and worked examples — is in
`docs/STATISTICAL_MODEL.md`.

---

## 11. Tuning the ore priors

The shipped priors are defensible defaults, not measurements of *your* world. This is the thing most
worth tuning, and it is a controlled procedure, not guesswork.

What the priors mean, for one ore: `hidden-discovery-rate-per-1000-blocks` is how many buried veins a
legitimate miner finds per thousand blocks of rock moved; `ore-informed-rate-multiplier` is how many
times more an ore-vision user finds; `legitimate-hidden-fraction` is the share of a legitimate
player's total discoveries that are buried rather than cave-exposed. Get the order of magnitude
right and the model is robust; a factor-of-two error shifts accumulated evidence slightly rather than
flipping verdicts.

Procedure:

1. **Consider switching to `ALERT_ONLY` and turning on analysis logging while you calibrate.** Set
   `debug.log-analysis: true` temporarily, and optionally `suspicion.enforcement-mode: ALERT_ONLY` so
   nothing accrues while you check the numbers.
   This logs the full evidence explanation for every assessment, which is exactly what you need to
   see whether the expectations match reality.
2. **Pick a reference cohort of players you are confident are legitimate** — for example your own
   account mined by hand, or long-standing builders who mine for materials. Have them play a normal
   session.
3. **Read `/xray stats <player>` for each.** Note, per ore, the buried discoveries, the blocks mined
   and the implied buried discovers per 1000 blocks mined. Compare that against the configured
   `hidden-discovery-rate-per-1000-blocks`.
4. **Adjust in the right direction.** If a known-legitimate player consistently achieves a higher
   buried rate than the prior predicts, the prior is too low for your world (richer geology, powerful
   pickaxes, a strategy that moves less waste rock) — raise it, so honest players are not measured
   against an impossible baseline. If your world is unusual in the other direction, lower it.
5. **Calibrate the mix baseline next.** `legitimate-hidden-fraction` should reflect how much of your
   reference players' discoveries are buried versus cave-exposed. Massively caved worlds need it
   lower than 0.55; solid-stone worlds higher. `/xray stats` gives you the hidden fraction directly.
6. **Set the guard rails.** Keep `minimum-sample-size` and `minimum-independent-groups` at their
   defaults (10 and 2) unless you have a reason. Raising them means slower, safer detection; lowering
   them buys speed at the cost of false positives. Two is the anti-evasion floor: one signal, however
   strong, is not enough.
7. **Re-test, then turn analysis logging off.** `debug.log-analysis` must not stay on in production.
8. **Re-evaluate after world changes.** A new mining world, a terrain plug-in, or a change to your
   economy that alters how players mine can invalidate priors you tuned months ago.

Repeat for each ore you actually mine. Emerald and ancient debris have genuinely different
generation from diamond; do not copy one ore's numbers onto another.

---

## 12. Backups

Everything the plugin knows lives in the database, so backing it up is the whole job.

- **SQLite.** The database is the file `plugins/XRayAntiCheat/xray.db`. In WAL mode there are also
  `xray.db-wal` and `xray.db-shm` alongside it. The safe way to back up a live SQLite database is to
  stop the server (or quiesce writes) and copy all three files together, or use `sqlite3 xray.db
  ".backup 'xray-backup.db'"`. Copying only the main file while writes are in flight can give you an
  inconsistent snapshot.
- **MariaDB.** `mysqldump --single-transaction xray_anticheat > xray-backup.sql`.
- **PostgreSQL.** `pg_dump xray_anticheat > xray-backup.sql`.

Back up before you change `database.yml`, before a major upgrade, and on whatever schedule your
other plugin data is on. The `messages.yml` and `gui.yml` files are also worth keeping in version
control or a config backup, since they represent staff effort.

---

## 13. Schema migrations

Migrations are **forward-only and additive**: they add tables and columns and never drop or rewrite
existing data. They live in `xray-persistence/src/main/resources/migrations/` (the initial schema is
`V1__initial_schema.sql`) and are applied at startup when `storage.migrations.run-on-startup: true`
(default). Applied versions are tracked in a `schema_migrations` table; the current version is shown
in `/xray status`.

- To apply migrations manually, set `run-on-startup: false` and run the SQL yourself in order, adding
  the corresponding rows to `schema_migrations`.
- The plugin's schema covers players, sessions, world modifications, mining events, ore discoveries
  and veins, suspicion snapshots, evidence events, ban-wave candidates and waves, moderator actions,
  world analysis and plugin metadata — broadly indexed on player and time for the queries the reports
  need.
- Because migrations never destroy data, an upgrade is not a data-loss event. Still take a backup
  first (§11); "additive" is a promise about the schema, not a substitute for having a restore point.

---

## 14. Troubleshooting

### The database is unreachable

Symptom: a console message that the database is unreachable, and `/xray status` shows the pool as
unavailable.

This is the fail-safe path, not a crash. With `continue-without-database: true` the plugin keeps
running with **reduced function**: analysis continues in memory and alerts are still raised, but
nothing is persisted and ban-wave enforcement is suspended (enforcement depends on stored evidence
being recomputed before anyone is removed). It retries every `retry-interval-seconds` and logs when
the connection is restored, after which buffered observations flush.

Check, in order: the host/port in `database.yml`; that the database actually exists (the operator
creates it — §3); that the user has privileges on it; that the credentials resolve (if you use the
environment variable, confirm the *server process* has it, not just your shell); and firewall or
`bind-address` settings on the database server.

### Running in degraded mode

This is the same state: analysis in memory, alerts raised, nothing stored, ban waves suspended. It is
safe but temporary by design — evidence is not accumulating on disk, so a player's stored history
will have a gap. Resolve the connection rather than letting it run this way indefinitely. If you
would rather the server not run an unpersisted anti-cheat at all, set
`continue-without-database: false`; the plugin will then disable itself on a failed connection.

### No alerts are firing

Work through, in order:

1. Is the plugin healthy? `/xray status` — check analysis is `on` and suspicion is `on`.
2. Is anyone tracked? `Tracked` should be non-zero when players are mining.
3. Is the world being analysed? `analysis.worlds` must include the player's world (or be `["*"]`).
4. Are the thresholds reachable? `minimum-strength-for-alert` and `minimum-confidence-for-alert`
   gate alerts, and the engine's `minimum-sample-size` and `minimum-independent-groups` gate any
   verdict at all. A quiet server with few observations will legitimately produce "insufficient
   evidence" for a long time — that is the system refusing to guess.
5. Are the ore profiles enabled? A disabled ore is excluded from analysis entirely.
6. Turn on `debug.log-analysis` briefly and watch what assessments are being produced.

### False-positive reports

A player (or your own staff) reports being flagged unfairly. Handling the *appeal* is covered in
`docs/MODERATOR_GUIDE.md`; handling the *cause* is here:

1. Collect the evidence with `/xray stats` and `/xray inspect`, and record a note.
2. Look at the buried/exposed split. A high exposed share is recorded exculpatory evidence — cave
   exploration and strip mining are legitimate and the exposure-mix component is designed to account
   for them. If a genuine cave explorer is scoring, your `legitimate-hidden-fraction` is probably too
   low for a cave-rich world.
3. Look at which signal carried the verdict. If it is a single family, the engine's own minimum-
   independent-groups gate should have blocked it; if a verdict is reaching you, at least two
   families agree, so re-check the priors that drive them.
4. Re-tune the priors as in §10, using this player as a reference point if they are genuinely honest.
5. If the pattern is general, raise `minimum-sample-size` and/or `minimum-confidence-for-alert`
   until your staff's workload matches your confidence. If it is specific to one ore in one world,
   fix that ore's rate.

Never "fix" a false positive by leaving the plugin in a mode where it acted — the default mode is
one where it cannot.

---

## 15. Limitations

Read these before trusting the system with enforcement.

- **Statistical evidence is not proof.** The system produces priors and likelihood ratios, not
  certainties. It exists to focus human attention, not to replace it.
- **Only SQLite is covered by automated tests.** MariaDB and PostgreSQL share the same code paths
  and the same dialect-neutral schema, but they are not exercised by the test suite. Validate them on
  staging before production, and be readier to check worker logs after an upgrade on those engines.
- **MariaDB and PostgreSQL are operator-created.** The plugin creates its own tables inside an
  existing database; it will not create the database.
- **The excavation ledger is bounded.** When it prunes, older excavation provenance is forgotten and
  the analyser reports natural terrain or `UNKNOWN` rather than guessing. This errs toward leniency:
  a forgotten excavation can make a genuinely buried ore look exposed, never the reverse. Lower
  `ledger-max-entries` on memory-constrained servers and accept slightly more leniency.
- **A long session can overflow the bounded discovery buffer**, which gradually makes the assessment
  more lenient. This is documented in `AnalysisService`.
- **No machine learning, no training phase, no calibration requirement.** It works from install with
  the shipped priors; those priors are the thing most worth tuning to a specific world, and a world
  whose terrain differs sharply from the defaults is where mis-tuning shows first.
- **The model is about ore-directed behaviour only.** It says nothing about flight, combat, reach or
  any other cheat. A low score is not a clean bill of health.
- **The weakest assumption is a homogeneous discovery rate.** Real rates vary with depth, biome,
  terrain and strategy. The mix and targeting signals partly compensate, but a player mining unusually
  rich ground can genuinely beat the expected rate.
- **Folia is not supported.**
