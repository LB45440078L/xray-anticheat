# XRay AntiCheat

A statistical X-ray / ore-vision anti-cheat for Spigot Minecraft servers. It observes how players
mine, accumulates a body of evidence for or against ore-informed behaviour, and explains its
reasoning to moderators in plain language.

---

## What this plugin is

XRay AntiCheat is a **statistical inference system**, not a threshold checker. It does not count
blocks, compare them against a fixed limit and flag whoever crosses it. Instead it maintains two
explicit hypotheses about a player —

- **H₀**: legitimate mining, and
- **H₁**: ore-informed (ore-vision) mining —

computes how much the observed behaviour favours H₁ over H₀, and accumulates that evidence over
time, decaying older observations and shrinking the weight of thin ones.

The output is a **likelihood ratio** expressed on the conventional forensic evidence scale (weak,
moderate, strong, very strong) together with a separate measure of how much the assessment can be
trusted given how much data it rests on. Every verdict is accompanied by a breakdown that names the
signals that contributed and whether each one argues for or against the player.

Two consequences of this design matter more than any other single thing in this README:

1. **The default enforcement mode is `BAN_WAVE`, with automatic execution off.** Out of the box the
   plugin accumulates candidates and plans waves, but bans nobody on its own: a wave waits for a
   moderator to approve it. Nothing is enforced unreviewed, and `ALERT_ONLY` remains available for
   pure observation.
2. **A statistic is not a verdict.** The system produces priors and likelihood ratios, not proof.
   A moderator reads the evidence and reaches their own conclusion. See
   `docs/MODERATOR_GUIDE.md` for the investigation workflow.

---

## Supported environment

| Component | Version |
| --- | --- |
| Server | Spigot, Minecraft 26.2 (`spigot-api 26.2-R0.1-SNAPSHOT`) |
| Java | Java 25 (class files `major 69`; compiled and tested with Temurin 27+35 via `--release 25`) |
| Build | Maven 3.9.16 |
| Folia | Not supported (regionised threading; the plugin is main-thread based) |

The plugin is built against Paper's API and is expected to run on any Paper-derived server for the
same Minecraft version. It has not been tested on Folia or on forks with a different threading
model.

---

## Building

The project is a Maven multi-module build with four modules:

- **`xray-core`** — the analytical engine. Pure Java, no Minecraft dependency, no database
  dependency. All of the statistics live here and are tested in isolation.
- **`xray-persistence`** — storage: HikariCP connection pooling and JDBC repositories for SQLite,
  MariaDB and PostgreSQL.
- **`xray-web`** — the administration panel: an embedded HTTP server over the repository interfaces,
  and its interface. Depends on nothing but the JDK and `xray-core`.
- **`xray-spigot`** — the Spigot plugin itself: event listeners, session tracking, commands, the
  moderator interface, the panel's lifecycle and enforcement. Uses no Paper-specific API.

```bash
mvn clean package
```

The build compiles the modules, runs the test suite (176 tests: 94 analytical core, 13 persistence
including SQLite integration, 56 for the administration panel - including a suite that drives the
real HTTP server against a real database - and 13 for the plugin) and produces the plugin jar at:

```
xray-spigot/target/xray-anticheat-1.0.1.jar
```

The jar is about **330 KB**. It contains only this project's own code and its configuration files:
the three sibling modules it needs (`xray-core`, `xray-persistence`, `xray-web`) are merged in,
because they are not published to Maven Central and cannot be fetched at runtime.

Everything external — HikariCP, the SQLite JDBC driver, the MariaDB and PostgreSQL drivers, and SLF4J
with its `java.util.logging` binding — is **downloaded by the server at runtime** and is deliberately not
bundled. Those six artifacts are declared under `libraries:` in `plugin.yml`: the server fetches them
from Maven Central on first start, adds them to the plugin's classpath and caches them in its
`libraries/` directory.

SLF4J is listed there explicitly, and that matters: `spigot-api` does not depend on it the way
`paper-api` did, and Spigot logs through `java.util.logging`. Assuming the server supplied it would have
left the plugin with no SLF4J at all and silently discarded every log line.

This is not a cosmetic saving. Bundling sqlite-jdbc alone cost 11.5 MB, because that artifact ships
native SQLite binaries for five platforms (Linux, Linux-Musl, Windows, Mac and FreeBSD) and the
plugin runs on one of them. See [Why the jar is small](#why-the-jar-is-small) for the full figures.

### Why the jar is small

Measured on the 1.0.0 build. The old jar was **14,569,935 bytes**; the new one is about **340 KB** — a
97.7% reduction. The exact byte count moves by a few hundred bytes depending on the JDK that built it
(340,833 locally, 341,565 in CI), because the compressor differs between them; the order of magnitude
is what matters, and it is enforced by the build.

Where the old jar's size actually went (compressed sizes, as stored in the jar):

| Component | Size | Now |
| --- | ---: | --- |
| sqlite-jdbc native libraries | 11,472 KB | fetched by the server |
| PostgreSQL JDBC driver | 1,082 KB | fetched by the server |
| MariaDB Connector/J | 680 KB | fetched by the server |
| this plugin's own code | 276 KB | **still in the jar** |
| sqlite-jdbc Java classes | 201 KB | fetched by the server |
| HikariCP | 144 KB | fetched by the server |
| META-INF and other metadata | 67 KB | mostly gone |
| slf4j-api | 55 KB | already provided by the server |

The single biggest item was 82% of the total: the native SQLite binaries inside `sqlite-jdbc`, which
cover Linux, Linux-Musl, Windows, Mac and FreeBSD — five platforms shipped in a download that runs on
one. That is not something a plugin should carry, and Paper has a supported way to avoid it: the
`libraries:` field in `plugin.yml`, which makes the server fetch Maven artifacts itself. Using it also
removes the reason shade and relocation existed in the first place.

Two of the three remaining kilobytes are the plugin's own code. What is left is genuinely what the
plugin is.

`tools/ci/verify_jar.sh` enforces a **4 MB budget** on the packaged jar and fails the build if a
dependency that lost its `provided` scope gets bundled again — the regression that would silently
take it back towards 14 MB.

---

## Installation

1. Build the jar, or obtain a release jar.
2. Drop `xray-anticheat-1.0.1.jar` into your server's `plugins/` directory.
3. Start (or restart) the server.

On first start the plugin creates `plugins/XRayAntiCheat/` and writes its four configuration files
into it. It also creates the SQLite database file at `plugins/XRayAntiCheat/xray.db` and applies its
schema. No external database is required for a single server.

**The first start needs network access to Maven Central**, so that the server can fetch the libraries
listed in `plugin.yml`. Paper caches them, so this happens once. If your server has no outbound
internet access, see
[Servers without internet access](docs/ADMIN_GUIDE.md#servers-without-internet-access) — you can
pre-seed the cache or point the server at an internal Maven mirror.

---

## First-run behaviour: nothing is enforced

**By default the plugin never acts against a player on its own.** It collects observations, analyses
them, raises alerts to staff holding `xray.alerts`, and holds qualifying players as candidates. It does
not kick, ban, freeze or otherwise affect anyone: with `ban-wave.automatic-ban: false` a wave is a
*proposal*, and the only ways enforcement happens are an administrator turning automatic execution on or
a moderator approving an action themselves.

This is intentional. The ore priors that ship with the plugin are sensible defaults, not measured
constants for *your* world, and a server should watch what the system produces — and compare it
against what staff know about their players — before it is trusted to act. Treat that period as
calibration, not as waiting; `/xray banwave plan` shows you what a wave would contain without executing
anything.

---

## Evidence accumulates across restarts

An assessment is not limited to the current login. Every evaluation folds in the player's **stored
record** — up to `analysis.history.lookback-days` (90 by default) of discoveries and mining events,
read on a worker thread and cached — alongside what the current session has just observed. Restarting
the server, or a player logging out and back in, therefore no longer resets the evidence base to zero.

This is what makes a ban wave defensible: a wave is justified by conduct that has lasted months, not by
a few hours that happen to look bad. It is also the reason the shipped retention is `30` days for mining
events rather than `7`: that table is what the historical trajectory is rebuilt from, and the loader
warns if the lookback reaches back further than the retention keeps.

Turn it off with `analysis.history.enabled: false` to restore session-only analysis. See
`docs/CONFIGURATION.md` §1 for what is reconstructed exactly and what is an approximation.

---

## Configuration

Four files are written to `plugins/XRayAntiCheat/`. Each is heavily commented in place; the
comments are the primary documentation and explain *why* each value is what it is. A full
key-by-key reference is in `docs/CONFIGURATION.md`.

| File | Contents |
| --- | --- |
| `config.yml` | The analytical model, ore priors, evidence thresholds, enforcement policy, tracking limits, performance and retention. The heart of the configuration. |
| `database.yml` | Storage engine selection and connection settings: SQLite path, or MariaDB/PostgreSQL host, port, credentials, pool and migration behaviour. |
| `messages.yml` | Every user-facing string — alerts, command output, status pages, error text. Nothing user-facing is hard-coded in Java. |
| `gui.yml` | The moderator interface: which menus exist, their items, lore, layout and the action each item performs. |

Changes are picked up by `/xray reload`, which is atomic: the whole validated settings object is
swapped at once, and in-memory observations are kept. A partially-applied reload would make stored
evidence incomparable, so it does not happen.

The single most consequential key in `config.yml` is
`ores.*.hidden-discovery-rate-per-1000-blocks` (together with `ore-informed-rate-multiplier`): it
states how many buried veins a legitimate miner finds per thousand blocks of rock moved. Getting its
order of magnitude right matters; a factor-of-two error shifts the accumulated evidence slightly
rather than flipping verdicts. See `docs/ADMIN_GUIDE.md` for a tuning procedure.

---

## Database options

Three engines are supported and treated identically by the plugin; only `database.yml` differs.

| Engine | When to use it | Notes |
| --- | --- | --- |
| **SQLite** (default) | A single server. | Nothing to install. The database is one file under the plugin folder. Entirely adequate for a single server. |
| **MariaDB** | A network of servers that should share one record, or when the ledger outgrows a single file. | The operator creates the database and user; the plugin creates its own tables. |
| **PostgreSQL** | As above, where PostgreSQL is the house standard. | Same split of responsibility. |

For MariaDB and PostgreSQL the **server operator must create the database** — the plugin only
creates and migrates its own tables inside an existing database. Credentials can be supplied through
an environment variable instead of being stored in `database.yml`, so a password does not end up in
backups or version control; the variable name is configurable
(`storage.*.password-environment-variable`, default `XRAY_DB_PASSWORD`). Setup examples, including
`CREATE DATABASE` statements, are in `docs/ADMIN_GUIDE.md`.

If the database is unreachable, the plugin logs the failure and keeps running with reduced function
rather than preventing the server from starting: analysis continues in memory and alerts are raised,
but nothing is persisted and ban-wave enforcement is suspended. It retries in the background.

---

## Commands

All commands live under `/xray`. Aliases are `/xrayac` and `/ac`.

| Command | Permission | What it does |
| --- | --- | --- |
| `/xray help` | `xray.inspect` | List the commands. |
| `/xray status` | `xray.admin` | Plugin version, enforcement mode, storage dialect and schema version, worker pool, tracked players, ledger size, candidate count, assessments and alerts today. |
| `/xray inspect <player>` | `xray.inspect` | The full evidence report: verdict, score, confidence, the contributing signals and their direction. Append `gui` to open the interface at the same time. |
| `/xray stats <player>` | `xray.inspect` | Per-ore mining statistics: blocks mined, distance travelled, and buried versus exposed discoveries per ore. |
| `/xray evidence <player>` | `xray.inspect` | The current evidence breakdown. This is the same content as `inspect`; it exists as a separate entry point so it can be granted independently. |
| `/xray history <player>` | `xray.inspect` | The most recent stored assessments, each with its band, score, confidence and signal count. |
| `/xray gui [player]` | `xray.inspect` | Open the moderator interface: the tracked-player list, or a specific player's detail page. |
| `/xray banwave [status\|plan\|approve]` | `xray.banwave` | Review ban-wave candidates (`status`), plan a wave (`plan`), or approve and execute one (`approve`). |
| `/xray note <player> <text>` | `xray.inspect` | Attach a note to a player's record. |
| `/xray reload` | `xray.reload` | Reload all four configuration files. |
| `/xray debug` | `xray.debug` | Toggle debug logging at runtime. |
| `/xray webpassword <new-password>` | `xray.web` | Set the administration panel password. It is hashed before storage and the panel restarts. |

Notes:

- Every subcommand checks its **own** permission. There is no single gate, because reading a status
  page, inspecting a player, teleporting to them and banning them are four different levels of
  trust.
- Opening an inspection — the command or the GUI — **generates no evidence** against the player.
  Looking at someone must not change their record in either direction.
- Reports that need stored history read it on a worker thread and answer when it arrives, so no
  command blocks the server tick loop on a database round trip.

---

## Permissions

The permission tree is graded deliberately; a single umbrella node would either over-restrict
ordinary moderators or hand every inspector the power to remove players.

| Permission | Grants |
| --- | --- |
| `xray.admin` | View plugin status and health (`/xray status`). |
| `xray.alerts` | Receive alerts when a player's evidence reaches a threshold. |
| `xray.inspect` | Inspect players, read evidence reports, open the moderator interface, set notes. |
| `xray.teleport` | Teleport to a player from the inspection interface. |
| `xray.freeze` | Hold a player in place while investigating. |
| `xray.kick` | Kick a player from the inspection interface. |
| `xray.ban` | Ban a player from the inspection interface. |
| `xray.banwave` | Review, plan and approve ban waves. |
| `xray.web` | Set the administration panel password. |
| `xray.reload` | Reload the plugin configuration. |
| `xray.debug` | Toggle debug logging. |
| `xray.*` | Umbrella node granting every permission above. |

All permissions default to `op`. Give `xray.ban`, `xray.kick`, `xray.banwave` and `xray.*` only to
staff you would already trust to remove a player by hand.

---

## Moderator interface

`/xray gui` opens a chest-menu interface, or use `/xray gui <player>` / `/xray inspect <player> gui`
to open a specific player. It is defined entirely in `gui.yml`, so it can be reletted or reordered
without touching the plugin.

- **Player list** — tracked players sorted by suspicion, most suspicious first. Each head shows the
  verdict, score, confidence, decibans, signals and the buried/exposed split.
- **Player detail** — the evidence report, mining statistics, and the moderation actions:
  teleport, teleport-while-vanished, spectate, freeze, add note, acknowledge alert, flag, kick and
  ban. Irreversible actions (kick, ban) open a confirmation menu first.
- **Statistics** — a per-ore breakdown of buried versus exposed discoveries.

The interface renders from in-memory state, not from the database, so it shows what is true *now*
rather than at the last flush. Permissions for an action are re-checked when the item is *clicked*,
not merely when the menu was drawn.

---

## Ban waves

Immediate banning has three problems: it removes a cheater while their technique still works, so the
community learns exactly what tripped detection; it acts on a single moment rather than a body of
evidence; and it produces a trickle of easily-attributed bans. Ban waves hold candidates, accumulate
their evidence, and enforce in a batch once enough independent cases exist and the stored evidence
has been recomputed.

This mode is the default: `suspicion.enforcement-mode: BAN_WAVE` in `config.yml`. With the default
`ban-wave.automatic-ban: false`, a wave only *proposes* a list; a moderator runs
`/xray banwave plan` to preview it and `/xray banwave approve` to execute it. Set
`automatic-ban: true` only once you trust the system, having run it manually for a while. Candidates
and waves are governed by `ban-wave.*` (interval, candidate TTL, minimum strength/confidence/signals
and the minimum number of candidates before a wave is worth running).

Under the shipped defaults a wave is planned and waits for approval. In `ALERT_ONLY` mode no candidate
is ever held, so no wave can be planned at all.

---

## The statistical model, in brief

The engine is a per-player, per-world, per-window Bayesian accumulator in **log-odds space**. Each
of five evidence components produces a log-likelihood ratio; the engine sums them after reliability
weighting, sample-size shrinkage and exponential time-decay, adds a prior, and bands the posterior
onto the forensic scale.

The five components are:

| Component | What it tests |
| --- | --- |
| **Hidden-discovery rate** | Are buried ore discoveries more frequent per block of rock moved than a legitimate yield predicts? |
| **Inter-discovery waiting** | Are the effort gaps between buried discoveries distributed as chance would produce, or too short and too uniform? |
| **Exposure mix** | Is the *proportion* of finds that were buried rather than cave-exposed higher than legitimate exploration would give? This is the signal that protects cave explorers. |
| **Ore targeting** | Before each buried ore became visible, were the player's movement and — more sharply — their camera already pointing at it through solid rock? |
| **Tunnel geometry** | A small, hard-capped modulator from the shape of the path. Deliberately bounded so it can never carry a verdict on its own. |

Evidence combines across **independent signal families**, not raw block counts. Two structural rules
keep this honest: the unit of observation is the vein, never the block (the blocks of a vein are
generated together and counting them separately would be pseudo-replication), and the rate and
waiting components share one family because they are two views of the same Poisson process. A
conclusion requires a minimum number of observations **and** a minimum number of independent
families, so no single signal — however strong — can carry a verdict. Below those minimums the
verdict is reported as *insufficient evidence*, whatever the raw numbers look like.

The priors that ship are, for diamond: a buried-vein rate of 1.5 per 1000 blocks mined with a 6×
ore-informed multiplier; emerald: 0.35 with 6×; ancient debris: 2.5 with 4×. The look-alignment
baseline of 0.067 is not a guess — it is the exact solid-angle fraction of a 30° cone,
`(1 − cos 30°)/2`. The default prior probability that an arbitrary player is cheating is 0.02, and
the observation half-life is 168 hours.

The full interpretation — assumptions, false-positive control, worked numerical examples and an
honest account of where the model can be wrong — is in **`docs/STATISTICAL_MODEL.md`**. The
derivation, mapped to the methods that implement it, is in `docs/MATHEMATICAL_MODEL.md`.

---

## Python visualiser

`tools/xray_visualizer.py` is an optional moderator tool. It reads the plugin's evidence store and
renders a single rotatable 3D scene per selection: the player's trajectory, the blocks they mined,
every ore discovery (coloured by ore, shaped and filled by exposure), and "targeting vectors" for
discoveries the player was already travelling almost directly at long before the ore could be seen.

```bash
python -m pip install -r tools/requirements.txt

# Synthetic data, no server needed:
python tools/xray_visualizer.py --demo

# Against a real SQLite database:
python tools/xray_visualizer.py --database-url sqlite:///plugins/XRayAntiCheat/xray.db --player Steve

# Headless PNG export:
python tools/xray_visualizer.py --demo --no-interactive --output scene.png
```

`numpy`, `pandas` and `matplotlib` are required. `scipy` is never required; SQLAlchemy is optional.
SQLite works out of the box; PostgreSQL and MariaDB are supported on a best-effort basis using
SQLAlchemy or the appropriate driver if SQLAlchemy is not installed.

---

## Performance

The overriding constraint is that this runs inside a Minecraft server with 50 ms per tick, so the
design follows one absolute rule:

> **The Minecraft server thread only collects lightweight observations and renders the interface.
> It never performs blocking database work, and it never runs the statistical analysis.**

This is enforced structurally, not by convention. `xray-core` holds neither a Minecraft `World` nor
a database connection, so an analysis component physically cannot load a chunk or open a
transaction; the only way to touch world state is through a server-thread-confined `WorldView`, and
the only way to touch storage is through repositories whose contract forbids server-thread use.
Observations are frozen into immutable records and handed to a worker pool.

Defaults: analysis and I/O run on virtual threads (`performance.use-virtual-threads: true`), with a
platform-thread fallback of four threads. Queued observations are flushed every 10 seconds in JDBC
batches of 500. Player buffers are bounded and pruned, which keeps memory flat on a busy server. The
excavation ledger defaults to 2,000,000 entries (on the order of 200 MB) and can be lowered on
memory-constrained servers.

No timing harness ships with the project, so treat the per-event figures in `docs/PERFORMANCE.md` as
a design budget rather than measured benchmark output.

---

## A worked alert

Alerts are terse on purpose — they point a moderator at the evidence rather than replacing it. An
alert looks like this (values illustrative):

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

**What a moderator should do with it.** Open the report with `/xray inspect Steve` (or the GUI) and
read the reasoning rather than the headline. Check the confidence and the observation count first: a
strong band resting on a thin sample is worth less than it looks, though the engine already gates on
that. Read each contribution and its direction — each line states the quantity and the expectation
it was compared against, so the reasoning can be checked. Look at the buried/exposed split with
`/xray stats Steve`: a high exposed share argues in the player's favour. Then go and look at the
player in world, because the statistics describe behaviour, not intent. Only then decide. **Do not
act on a single statistic.** The full workflow, including what an alert does *not* mean, is in
`docs/MODERATOR_GUIDE.md`.

---

## Limitations

These are known, true, and worth reading before you trust the system with enforcement.

- **Statistical evidence is not proof.** The system produces priors and likelihood ratios, not
  certainties. It is a tool for focusing human attention, not a replacement for human judgement.
- **Only SQLite is covered by automated tests.** MariaDB and PostgreSQL share the same code paths
  and the same dialect-neutral schema, but they are not exercised by the test suite and should be
  validated on a staging server before production use.
- **MariaDB and PostgreSQL are operator-created.** The plugin creates its own tables inside an
  existing database; it does not create the database itself.
- **The in-memory excavation ledger is bounded.** When it prunes, older excavation provenance is
  forgotten, and the exposure analyser reports natural terrain or `UNKNOWN` rather than guessing.
  This errs toward leniency — a forgotten excavation can make a genuinely buried ore look exposed,
  never the reverse.
- **A long session can overflow the bounded *in-memory* discovery buffer.** As the buffer discards its
  oldest entries, the live part of an assessment gradually becomes more lenient. This is far less
  consequential than it used to be: with `analysis.history.enabled` (the default) the assessment is
  rebuilt from the database as well, so the discarded entries are still counted — the bound now limits
  how much of the newest session is held in memory, not how far back the evidence reaches.
- **Stored history is rebuilt, and one interval at its seam is dropped.** Discoveries, effort and
  alignment angles are stored in full; distance travelled is a lower bound, and the historical
  trajectory is a path of mined blocks rather than of the player's walking. Where it meets the live
  session, the gap is unmeasurable, so that one waiting-time observation is discarded rather than
  guessed — always the lenient direction. See `docs/STATISTICAL_MODEL.md` §12.
- **There is no machine learning, no training phase and no calibration requirement.** It works from
  install using the shipped priors. Those priors are the thing most worth tuning to a specific
  world, and a world whose terrain differs sharply from the defaults is where mis-tuning will show
  first.
- **The model is specifically about ore-directed behaviour.** It makes no judgement about flight,
  combat, reach, or any other form of cheating. A low score is not a clean bill of health; it means
  no evidence of *ore-vision* behaviour.
- **The model's weakest assumption is a homogeneous discovery rate.** Real rate varies with depth,
  biome, terrain and strategy. The mix and targeting signals partly compensate, but a player mining
  in an unusually rich area can genuinely beat the expected rate.
