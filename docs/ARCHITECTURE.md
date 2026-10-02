# Architecture

This document describes how XRay AntiCheat is put together: the layers, the three Maven
modules and why they are split the way they are, the outbound ports and their adapters, the
design patterns actually present in the code and the reason each one is there, the threading
model, and the lifecycle of a piece of evidence. The mathematics itself is not repeated here;
it is derived in [`MATHEMATICAL_MODEL.md`](MATHEMATICAL_MODEL.md) and interpreted in
[`STATISTICAL_MODEL.md`](STATISTICAL_MODEL.md).

The implementation is complete across all three modules: the analytical core, the JDBC
persistence layer, and the Paper adapter (listeners, session tracking, worker pool, alerts,
commands, GUI and enforcement). `mvn clean package` succeeds and all 84 tests pass — 77 unit
tests in `xray-core` and 7 SQLite integration tests in `xray-persistence`.

---

## 1. Layered design

The system is a hexagonal (ports-and-adapters) design with a pure analytical core at the
centre. Data flows inward from the Minecraft server as platform-neutral observations, is
transformed through world/geometry/ore analysis into a frozen analysis window, is scored by
the statistics and evidence layers, and emerges as a decision, an alert and a record.

```
                         ┌───────────────────────────────────────────────┐
                         │                 xray-paper                    │
                         │  (the only module that touches Bukkit/Paper)  │
                         │                                               │
  Minecraft events  ───▶ │  ObservationListener ──▶ PlayerSession ─────┐ │
  (server thread)        │   (MONITOR priority)      (bounded buffers) │ │
                         │                                              │ │
                         │  runSync(...)  ◀── alerts / kick / GUI ──────┘ │
                         └──────┬─────────────────────────┬────────────┘
                                │ Observation records     │ repository calls
                                ▼ (platform-neutral)      ▼ (worker threads)
                         ┌───────────────────────────────────────────────┐
                         │                 xray-core                     │
                         │                                               │
                         │  domain/        Observation, PlayerRef, ...   │
                         │  geom/          Vector3, BlockPos, PCA        │
                         │  world/         ExposureAnalyzer, VeinAnalyzer│
                         │  analysis/      TrajectoryAnalysis, Window,   │
                         │                 OreDiscovery                  │
                         │  statistics/    LikelihoodRatios, LogOdds, ...│
                         │  evidence/      EvidenceEngine + 5 components │
                         │  decision/      DecisionEngine, BanWavePlanner│
                         │  config/        OreProfile, MapOreProfileReg. │
                         │  port/          WorldView, OreCatalog,        │
                         │                 OreProfileRegistry            │
                         │  repository/    seven repository interfaces   │
                         └──────┬─────────────────────────┬──────────────┘
                                │                         │
                                ▼                         ▼
                         ┌───────────────────┐   ┌───────────────────────┐
                         │  xray-persistence │   │  moderator surface     │
                         │  HikariCP + JDBC  │   │  XRayCommand,          │
                         │  Migrations, Jdbc*│   │  GuiManager            │
                         └─────────┬─────────┘   └───────────────────────┘
                                   ▼
                    SQLite / MariaDB / PostgreSQL
```

The named layers, inward to outward:

| Layer | Lives in | Key types |
| --- | --- | --- |
| Adapter / event translation | `xray-paper` | `ObservationListener`, `BukkitWorldView`, `MaterialClassifier`, `ExcavationLedger`, `ConfigLoader`, `PluginSettings` |
| Observation collection | `xray-paper` | `PlayerSession`, `SessionRegistry`, `PendingPersistence` |
| Domain (platform-neutral facts) | `xray-core/domain` | `Observation` (sealed), `PlayerRef`, `WorldId`, `VeinObservation`, `ExposureState`, `ExposureResult`, `MiningOrigin` |
| World / geometry | `xray-core/world`, `geom` | `ExposureAnalyzer`, `VeinAnalyzer`, `BlockKind`, `Vector3`, `BlockPos`, `PrincipalAxes` |
| Behaviour / analysis window | `xray-core/analysis` | `TrajectoryAnalysis`, `PlayerAnalysisWindow`, `OreDiscovery` |
| History reconstruction | `xray-core/analysis` | `HistoryHydrator`, `AnalysisHistory`, `HistoryParameters` |
| Statistics | `xray-core/statistics` | `LikelihoodRatios`, `LogOdds`, the distribution types, `SpecialFunctions` |
| Evidence | `xray-core/evidence` | `EvidenceEngine`, `EvidenceComponent`, 5 components, `SuspicionSnapshot` |
| Decision | `xray-core/decision` | `DecisionEngine`, `DecisionPolicy`, `BanWavePlanner` |
| Actions / enforcement | `xray-paper/enforcement`, `alert`, `command`, `gui` | `EnforcementService`, `AlertService`, `XRayCommand`, `GuiManager` |
| Persistence | `xray-persistence` | `ConnectionProvider`, `TransactionManager`, `MigrationRunner`, seven `Jdbc*Repository`, wired by `PersistenceBundle` (paper) |
| Composition root | `xray-paper` | `XRayAntiCheatPlugin` |

The critical property of this arrangement is the direction of dependency: nothing in
`xray-core` depends on `xray-paper` or on Bukkit, and `xray-persistence` depends only on
`xray-core`. The arrows point inward.

---

## 2. The three modules, and why `xray-core` has no Minecraft dependency

```
xray-anticheat-parent (pom)
├── xray-core          pure Java; depends only on slf4j-api
├── xray-persistence   depends on xray-core + HikariCP + the three JDBC drivers
└── xray-paper         depends on xray-core + xray-persistence + paper-api
```

`xray-core`'s `pom.xml` declares exactly one runtime dependency (`slf4j-api`) and no
Minecraft API. This is not tidiness for its own sake; it buys four concrete things:

1. **Testability without a server.** The entire engine — geometry, statistics, the five
   evidence components, the decision policy — is exercised by 77 unit tests running in a plain
   JVM. `EvidenceEngineTest`, `ExposureAnalyzerTest`, `VeinAnalyzerTest`, `DecisionEngineTest`
   and `StatisticalPrimitivesTest` never start a Minecraft server, never need a world, and run
   in seconds. A core that imported `org.bukkit.World` could not be tested this way.
2. **Portability across server platforms.** The mathematics is expressed against
   `Vector3`/`BlockPos` and the outbound ports, not against Bukkit. Moving the adapter to a
   different server platform would touch `xray-paper` alone.
3. **No accidental server-thread work.** The core cannot load a chunk or start a synchronous
   database call, because it holds no `World` and no `Connection`. This is what makes the
   threading model in §5 enforceable rather than aspirational: the types simply do not permit
   the mistake.
4. **A stable seam for testing the persistence layer.** Because `xray-persistence` implements
   interfaces declared in `xray-core`, the integration tests can substitute an embedded SQLite
   database and exercise the real repositories without a Minecraft dependency leaking in.

The Paper module is the "only place that touches Bukkit/Paper types", and that is true:
`ObservationListener`, `BukkitWorldView`, `MaterialClassifier`, `GuiManager`, `XRayCommand`,
`AlertService`, `EnforcementService`, `PersistenceBundle`, `ConfigLoader` and `PluginSettings`
all import Bukkit types; nothing in `xray-core` or `xray-persistence` does.

---

## 3. Outbound ports and their adapters

An **outbound port** is an interface the core declares for something it needs from the
outside world, so that the core depends on an abstraction rather than on a concrete
implementation. The core defines the port; an adapter implements it.

### 3.1 `WorldView`

`io.xrayac.core.port.WorldView` is read-only access to world state: `isLoaded`, `kindAt`,
`blockKeyAt`, `originAt`, `removalEpochMillis`. Its Javadoc states the threading contract
plainly: implementations are **not** required to be thread-safe, all calls happen on the
server thread during observation collection, and implementations must never block on I/O.

- **Production adapter:** `io.xrayac.paper.adapter.BukkitWorldView`. It reads live chunks via
  `Bukkit.getWorld(...)`, and its `isLoaded` deliberately checks `World#isChunkLoaded` (chunk
  coordinates, not block coordinates) rather than reading a block in an unloaded chunk — a
  block read there would make the server synchronously load the chunk, a latency spike triggered
  by a block break. It returns `UNKNOWN`/`null`/empty so the analyser degrades honestly instead.
  Its `originAt` reports air or fluid with no ledger record as `MiningOrigin.NATURAL_TERRAIN`
  (an explicit, documented assumption whose error always makes the analyser more lenient).
- **Test adapter:** `FakeWorldView` (`xray-core/src/test`) is an in-memory implementation
  defaulting to solid, natural, unexcavated stone, with helpers (`naturalCavity`,
  `playerExcavated`, `otherPlayerExcavated`, `unloaded`, …) that let each test carve out one
  scenario. This is what makes `ExposureAnalyzerTest` and `VeinAnalyzerTest` possible.

`BukkitWorldView` is backed by `ExcavationLedger` (the in-memory provenance record) and
`MaterialClassifier` (the `Material → BlockKind` mapping, derived from `isOccluding()` and
`isSolid()` so new game versions and data packs are handled without a hard-coded list).

### 3.2 `OreCatalog`

`io.xrayac.core.port.OreCatalog` maps a raw block key (`minecraft:deepslate_diamond_ore`) to a
canonical ore id (`diamond`), and answers `isOre`/`sameOre`. It is a port because "which
materials count as the ore diamond" is a server configuration decision, not a fact the core
can assume.

- **Adapter:** `MapOreCatalog`, an immutable map-backed implementation with a validating
  `Builder` (it rejects a block key mapped to two different ores) and tolerance for keys
  written without a namespace (`diamond_ore` resolves against `minecraft:diamond_ore`).
- **Wiring:** `PluginSettings.oreCatalog()` builds one from the enabled ore profiles and
  deliberately omits disabled ores, so switching an ore off removes its blocks from the
  analysis entirely. The `ObservationListener` calls it per block break through
  `PluginSettings.oreCatalog()`.

### 3.3 `OreProfileRegistry`

`io.xrayac.core.config.OreProfileRegistry` looks up an `OreProfile` by canonical ore id. It is
a port rather than a concrete map so the Paper adapter can build it from `config.yml` while
the engine and every test see the same interface.

- **Adapter:** `MapOreProfileRegistry`, immutable and backed by `Map.copyOf`. Its
  `defaults()` method ships the three built-in profiles (diamond, emerald, ancient debris).
- **Wiring:** `PluginSettings.oreProfileRegistry()`, consulted by `AnalysisService` when it
  evaluates a window.

### 3.4 Repository interfaces

The seven repository interfaces live in `io.xrayac.core.repository` and are implemented in
`xray-persistence/jdbc`. Every one carries the same Javadoc contract: **blocking I/O; never
call from the server thread.**

| Port (core) | Adapter (persistence) | Holds |
| --- | --- | --- |
| `PlayerRepository` | `JdbcPlayerRepository` | identity, first/last seen |
| `MiningEventRepository` | `JdbcMiningEventRepository` | per-block breaks (trajectory source) |
| `OreDiscoveryRepository` | `JdbcOreDiscoveryRepository` | per-vein discoveries |
| `WorldModificationRepository` | `JdbcWorldModificationRepository` | the excavation ledger |
| `SuspicionRepository` | `JdbcSuspicionRepository` | snapshots + evidence events |
| `BanWaveRepository` | `JdbcBanWaveRepository` | candidates + wave history |
| `ModeratorActionRepository` | `JdbcModeratorActionRepository` | the moderator audit trail |

`PersistenceException` is an **unchecked** exception declared in the core, deliberately not
`java.sql.SQLException`: the analysis and plugin layers must not be aware that JDBC exists.
`JdbcRepository.read`/`write` is the single place where `SQLException` is translated.

The bundle that owns the pool and the seven concrete repositories, and tracks degraded
availability, is `io.xrayac.paper.persistence.PersistenceBundle`.

---

## 4. Design patterns, and why each is present

The project brief asks for the reason behind each pattern. The patterns below are ones that
are genuinely in the code, named with their real classes. Anything that is not in the code is
not listed.

### Strategy — `EvidenceComponent`

`EvidenceComponent` is an interface with a single `evaluate(window, profiles, parameters)`
method; the five implementations (`HiddenDiscoveryRateComponent`,
`InterDiscoveryWaitingComponent`, `OreTargetingComponent`, `ExposureMixComponent`,
`TunnelGeometryComponent`) are interchangeable algorithms that the `EvidenceEngine` treats
identically. This is Strategy, and it is the extension point of the whole system: adding a new
signal is adding one implementation and registering it, not editing the engine. It also makes
each component a **pure function of its arguments**, so it is trivially unit-testable, the
engine can run them in any order, and the same window always yields the same verdict (which is
what makes the system auditable).

### Factory — `OreProfile.Builder`, `MapOreCatalog.Builder`, `MapOreProfileRegistry.of`

`OreProfile.builder(oreId, displayName)` returns a named-parameter `Builder` that produces a
validated `OreProfile`. The Javadoc gives the reason: a fifteen-argument constructor makes an
accidental swap of two adjacent `double`s impossible to see at the call site and catastrophic
in the model. `MapOreCatalog.Builder` and `MapOreProfileRegistry.of(List<OreProfile>)` are
similar construction points, the latter rejecting duplicate ore ids at build time.

### Repository — the seven `*Repository` interfaces

Repositories mediate between the domain and the storage engine: the core asks for
`findRecent(playerId, since, limit)`, not for SQL. This is what lets `xray-core` be tested
without a database and what keeps the storage engine replaceable — the JDBC adapters are the
only classes that know a `Connection` exists.

### Observer — Bukkit event listeners

`ObservationListener implements Listener` subscribes to `BlockBreakEvent`, `PlayerMoveEvent`,
`PlayerJoinEvent`, `PlayerQuitEvent` and `PlayerChangedWorldEvent`, all at
`EventPriority.MONITOR` (observe, never change) with `ignoreCancelled` on the block break — a
break a protection plugin refused was not mined and must not count. `GuiManager` is likewise a
`Listener` for `InventoryClickEvent`/`InventoryCloseEvent`. This is Observer: the plugin reacts
to server events rather than polling.

### Adapter — `xray-paper`, and value translation

The Paper module is an Adapter in the ports-and-adapters sense: `ObservationListener` and
`BukkitWorldView` translate Bukkit events and live world state into the core's plain values;
`ConfigLoader` adapts YAML into typed records; `MapOreCatalog` and `MapOreProfileRegistry`
adapt configuration into the core's ports; `PersistenceBundle` and `JdbcRepository` adapt JDBC
into the core's repository ports; `MessageService` adapts `messages.yml` into text.

### Builder — as above

`OreProfile.Builder` and `MapOreCatalog.Builder` are the two Builders. Both defer validation
until `build()`, and both return `this` for chaining.

### Specification-like policy objects

Several small immutable records encapsulate "a rule plus its parameters" and are consulted by
code that does not know the rule's details:

- `ExposurePolicy` — diagonal visibility, the recent-excavation window, the vantage-point
  radius, whether attribution is required.
- `EvidenceParameters` — the prior, half-life, shrink constant, confidence scales, minimum
  sample and group gates, lookback distance, the mix baselines, and the derived
  `decayWeight`/`shrinkFactor`/`statisticalConfidence` methods.
- `DecisionPolicy` (with its nested `BanWavePolicy`) — the mode, the two confidence floors and
  the per-action evidence bands.
- `OreProfile` — per-ore generation bands, rate priors and alignment models.

They are "specification-like" rather than a formal Specification type: they do not compose with
`and`/`or`; they are configuration-shaped value objects whose constructors validate their own
invariants (for example, `DecisionPolicy` refuses a ban band weaker than the alert band).

### Chain of Responsibility — not present as such

There is no classic Chain of Responsibility handler chain. The closest analogues are (a) the
`EvidenceEngine` evaluating its component list in order and combining the results, where one
component throwing is caught and reduced to a non-informative contribution so the chain
continues (`EvidenceEngine.evaluateSafely`), and (b) the ordered gate sequence in
`DecisionEngine.decide`. Neither is a Handler/next-handler chain, and the documentation does not
claim one.

### Dependency injection by constructor wiring

There is no DI framework. Collaborators are passed to constructors and held in `final` fields:
`EvidenceEngine(List<EvidenceComponent>)`, `VeinAnalyzer(OreCatalog, ExposureAnalyzer, int)`,
`ExposureAnalyzer(ExposurePolicy)`, `DecisionEngine(DecisionPolicy)`, `MigrationRunner(
ConnectionProvider)`, `ObservationListener(...)`, `AnalysisService(...)`, and every
`Jdbc*Repository(ConnectionProvider[, int batchSize])`. The composition root is
`XRayAntiCheatPlugin.onEnable`, which builds the object graph explicitly and schedules the
background work. Its own Javadoc explains the choice: a DI framework would add startup cost, a
reflective failure mode and a configuration surface for a graph of about a dozen objects, while
obscuring the one thing a reader most needs to see — which component owns which thread.

### Message template method — `MessageService`

`MessageService` centralises all user-facing text, resolving from `messages.yml` with
`%placeholder%` substitution and a `%prefix%` expansion. A missing key returns a loud
`<missing: path>` rather than an empty string, so a gap is traceable rather than invisible.

---

## 5. Threading model

The governing rule is stated in the repository interfaces and the `WorldView` port:

> **The Minecraft server thread only collects lightweight observations and renders the
> interface. Workers evaluate evidence, write to the database and run retention. Nothing that
> blocks runs on the server thread, and nothing that touches the Bukkit API runs on a worker.**

The two bridges between the threads are `XRayAntiCheatPlugin.runAsync(Runnable)` (hands a task
to the worker pool) and `XRayAntiCheatPlugin.runSync(Runnable)` (schedules a task back onto the
server thread via `Bukkit.getScheduler().runTask`). `AnalysisService.onMainThread` is the same
idea local to the analysis pipeline.

```
  server thread (20 TPS budget)                 worker pool (virtual or fixed platform threads)
  ────────────────────────────                  ──────────────────────────────────────────────
  Bukkit event fires (ObservationListener)
        │
        ├─ build a platform-neutral Observation (immutable, self-contained)
        ├─ exposure/vein analysis reads chunks through WorldView   ← allowed: lightweight, no I/O
        ├─ append to the player's bounded PlayerSession buffers
        └─ enqueue for persistence (PendingPersistence)            ──────▶ flush on a worker
        │
        └─ PlayerSession.snapshot() -> AnalysisService.submit(window) ──▶ EvidenceEngine.evaluate(...)
                                                                             │  (pure CPU, no server state)
                                                                             ▼
                                                                        DecisionEngine.decide(...)
                                                                             │
                                                                        SuspicionRepository.save(...)
                                                                        BanWaveRepository.upsert(...)
                                                                             │  (blocking JDBC — worker only)
                                                                             ▼
        ◀──────── runSync(...) ────── alert / kick / enforce (server thread only) ─────────────┘
```

Why the split is drawn where it is:

- **Observation and exposure analysis stay on the server thread.** `ExposureAnalyzer` and
  `VeinAnalyzer` read blocks, and `WorldView`'s contract confines them to the server thread.
  Reading a block from a worker would either load a chunk asynchronously (a performance
  disaster) or return a default that looks like solid stone and would fabricate "hidden ore"
  evidence. The bounding safeguard is `VeinAnalyzer`'s `maxVeinSize` (default 64): one block
  break can never trigger an unbounded world scan.
- **Statistics and persistence run on workers.** Once the observations are frozen into an
  immutable `PlayerAnalysisWindow`, nothing downstream touches live server state, so the whole
  evidence pass needs no locks and cannot observe a half-updated player. `PlayerAnalysisWindow`
  and `SuspicionSnapshot` copy their lists defensively (`List.copyOf`), which is what makes
  the hand-off safe.
- **Server ownership is explicit in the session design.** `PlayerSession` and its owner
  `SessionRegistry` are server-thread-confined and use unsynchronised `ArrayDeque`/`HashMap`
  on purpose (the absence of a lock is a deliberate assertion that no worker touches them).
  Workers receive only frozen windows.
- **Minecraft API calls are scheduled back.** Alerts (`AlertService`), enforcement
  (`EnforcementService`) and the GUI all run on the server thread; the worker pipeline reaches
  them only through `runSync`/`onMainThread`.

`Observation` is a `sealed interface` with three variants (`Movement`, `Orientation`,
`Mining`), and each variant is immutable and self-contained — it carries its own player, world,
tick and timestamp — precisely so it can be handed to a worker and persisted later without
cross-thread reads of mutable server state. `Observation.Movement` even carries a `teleported`
flag, because a teleport's displacement is not a trajectory and must never be fed to the
geometry engine.

### 5.1 The scheduled work

`XRayAntiCheatPlugin.scheduleTasks` sets up the recurring jobs. Periods are converted to ticks
(× 20 per second; analysis/flush minutes × 60 × 20):

| Task | Thread | Period | Does |
| --- | --- | --- | --- |
| `analyseActiveSessions` | server | `tracking.analysis-interval-minutes` | snapshots every active session with activity and submits it to a worker |
| `flushPendingObservations` + `flushExcavationLedger` | worker (`runTaskTimerAsynchronously`) | `performance.flush-interval-seconds` | drains the observation queues and the ledger's pending-write queue into the database |
| `pruneSessions` | server | 600 ticks (30 s) | `SessionRegistry.pruneIdle` (finalising idle sessions) and `ExcavationLedger.pruneOlderThan` |
| `pruneRetention` | worker | 72 000 ticks (≈ 1 h), acted on only during `retention.prune-hour` | runs the four retention deletes |
| `attemptDatabaseRecovery` | worker | `fail-safe.retry-interval-seconds` | only scheduled while the store is unavailable |
| automatic ban wave | server | `ban-wave.interval-minutes` | only when mode is `BAN_WAVE` and `automatic-ban: true` |

On shutdown, `onDisable` truncates the worker pool (10 s grace, then `shutdownNow`) and then
flushes the pending observations and ledger **inline**, because the tick loop no longer exists
to protect and handing the final write to a pool being torn down is how the last minutes of a
session are lost.

---

## 6. Lifecycle of a piece of evidence

1. **Observe.** `ObservationListener.onBlockBreak` (MONITOR, ignore-cancelled) runs on the
   server thread. It checks the world is analysed, gets or creates the player's `PlayerSession`,
   and — if the broken block is a configured ore — reconstructs and classifies the vein.
2. **Classify exposure.** `VeinAnalyzer` reconstructs the connected vein (26-connectivity,
   bounded by `maxVeinSize`) and `ExposureAnalyzer` classifies each block as `FULLY_EXPOSED`,
   `PARTIALLY_EXPOSED`, `CONDITIONALLY_EXPOSED`, `HIDDEN` or `UNKNOWN`, consulting `WorldView`
   for block kinds, removal provenance and removal times. The classification distinguishes
   *natural* exposure from the observing player's *own recent* excavation (inside
   `recentExcavationWindowSeconds`) from *older or other players'* excavation — what stops a
   strip-mined ore counting as "visible" and an ore in someone else's tunnel counting as
   "hidden". The listener then records the block's removal in the ledger *after* the analysis,
   so a later ore sees this opening and correctly treats it as the player's own approach.

   **The vein is judged by its most visible member, not by the block that was broken.**
   `VeinObservation.aggregateExposure()` reduces the members with `ExposureState.mostVisible`,
   which takes the smallest hiddenness weight while ranking `UNKNOWN` above `HIDDEN`. A player who
   can see one block of a vein can reasonably mine all of it, so a vein with any visible member is
   one they could have found by ordinary play. Judging on the first block struck was a systematic
   false-positive bias: entering a vein from the side, or from the neighbouring tunnel the player
   was digging, means the first block broken is frequently enclosed even though the vein is open to
   a cave.

   **One vein is one discovery.** `PlayerSession.isVeinAccounted`/`accountVein` mark every block of
   a recorded vein, and the listener skips any block already accounted for. Without it a
   partly-visible vein yielded one discovery per block, and the later ones skewed towards `HIDDEN`:
   by then the player's own earlier breaks are the fresh openings beside them. The accounted set is
   bounded by `miningBufferSize`, discarding the oldest veins. `PartiallyVisibleVeinScenarioTest`
   covers both rules by running the real analyser over a synthetic world and feeding the result to
   the real engine: identical mining scores ~1e-11 judged by the vein and 0.997 (`STRONG`) judged by
   the broken block.
3. **Accumulate.** `PlayerSession` (one per player, per world) keeps bounded buffers of path
   points, mining events and discoveries, and running scalars (blocks mined, distance
   travelled, effort since the last discovery). Every buffer discards its oldest entry on
   overflow.
4. **Freeze a window.** `PlayerSession.snapshot(now)` produces an immutable
   `PlayerAnalysisWindow` — scoped to one player and one world (overworld and nether are never
   pooled) — which `AnalysisService.submit` hands to a worker. This happens on a periodic pass,
   on quit, on world change, and on idle prune.
5. **Evaluate.** On the worker, `EvidenceEngine.evaluate(window, profiles, parameters, now)`
   runs each `EvidenceComponent` safely (a throwing component becomes a non-informative
   contribution with the failure quoted). Each component returns an `EvidenceContribution`: a
   signed log-likelihood ratio, a sample size, a reliability, an `independentGroup`, a
   human-readable explanation and a metrics map.
6. **Combine.** Contributions are summed **within** their `independentGroup` and then across
   groups; the sample size is the **maximum** across groups (never the sum, so correlated
   views of one process cannot triple-count the evidence base); a single global shrinkage
   `n/(n+κ)` and a mean time-decay `φ` are applied to the whole raw sum; the prior is added and
   the posterior is clamped to `±30`.
7. **Band.** `EvidenceStrength.fromLogOdds(...)` maps the posterior to
   `INSUFFICIENT`/`WEAK`/`MODERATE`/`STRONG`/`VERY_STRONG`, refusing any band below the
   minimum sample size and minimum independent groups.
8. **Decide.** `DecisionEngine.decide(snapshot, now)` applies `DecisionPolicy`: an alert gate
   (band + confidence floor), then the strongest justified action, then the enforcement mode,
   which can only ever *downgrade* an action.
9. **Persist.** `SuspicionRepository.save` writes the snapshot and all of its evidence rows
   atomically. The latest snapshot is also cached in `AnalysisService` so the server-thread GUI
   can render without a blocking read. If the action is `CANDIDATE`, `BanWavePlanner.consider`
   updates the in-memory candidate list and `BanWaveRepository.upsertCandidate` persists it.
10. **Act.** Scheduled back onto the server thread: an `ALERT` calls `AlertService.alert`
    (throttled per player); a `KICK`/`BAN` calls `EnforcementService.accept`. In `BAN_WAVE`
    mode an `approve` command or the automatic-wave task calls `EnforcementService.executeWave`,
    which bans each candidate and records the audit entry.

### 6.1 Where a player's stored past rejoins the flow

Between steps 4 and 5 the worker folds the player's stored past into the frozen window, so an assessment
describes their whole recorded time rather than the current login — the difference between a ban wave
that can be defended and one that cannot.

- `HistoryHydrator.load` performs two indexed reads (`ore_discoveries`, `mining_events`) scoped to one
  player and one world, bounded by `analysis.history.max-discoveries` / `max-mining-events`. Both
  queries return newest-first at the database and the hydrator reverses them, because everything
  downstream reads oldest-first.
- `HistoryHydrator.merge` unions the stored discoveries with the session's, de-duplicating the events
  that are legitimately in both (a session writes its discoveries as it goes), sums the effort totals,
  concatenates the stored mining path with the live movement path in time order, and moves the window
  start back to the earliest stored observation, so the horizon reaches the explanation a moderator
  reads.
- The seam between stored history and the live session is recorded on the window as
  `liveDiscoveryIndex`. Its length is unknowable — the server may have been down, or the player may have
  played unrecorded — so the waiting-time model drops that one interval rather than guessing it.
- All of this runs on the worker: the reads block, which is precisely why they cannot happen in the
  collector on the server thread. The result is cached per player and world for
  `analysis.history.refresh-minutes`, so a pass normally performs no history I/O at all. A failed read is
  logged at warn and the assessment proceeds session-only — narrower than configured, and said out loud
  rather than passed over in silence, because a silent fallback is exactly the reset the feature exists
  to remove.

The reads go through the `OreDiscoveryRepository` and `MiningEventRepository` interfaces the core
defines, so the hydrator neither knows nor cares whether it is talking to SQLite, MariaDB or PostgreSQL,
and it is tested against in-memory fakes that reproduce the real queries' newest-first ordering.

---

## 7. The anti-evasion design, and how it is expressed in code

The project brief's central design rule is: **never rely on one strong threshold; require
multiple weak, independent signals to agree.** This is expressed structurally, not as a
comment:

- **Components are deliberately weak and bounded.** `TunnelGeometryComponent` caps its
  contribution at a likelihood ratio of 2 in either direction and declares reliability 0.4;
  it can never carry a verdict. The movement-alignment model is intentionally weak (0.5
  legitimate vs 0.8 informed) so a strip-miner is not flagged, while the look-alignment model
  (geometric baseline 0.067 vs 0.5) is the sharper one. The exposure-mix component returns
  *negative* evidence for a cave explorer, so a superficially alarming count is offset by the
  explanation.
- **Independence is declared and counted.** Each contribution names an `independentGroup`;
  components measuring the same underlying signal share a group (`discovery-rate` for the rate
  and waiting-time components, `targeting` for movement and look, `exposure-mix`, `geometry`).
  The engine sets `independentGroups` to the number of distinct contributing groups, and
  `EvidenceParameters.minimumIndependentGroups` (default 2) is a hard gate: any single signal,
  however strong, yields `INSUFFICIENT`.
- **Small samples cannot move the verdict.** `sampleSizeShrinkConstant` (5) scales the whole
  sum toward zero; `minimumSampleSize` (10) is a hard gate.
- **Two gates before enforcement.** `DecisionPolicy` requires *both* an evidence band *and* a
  statistical confidence floor, with a higher floor for irreversible actions
  (`minimumConfidenceForBan` 0.75 vs `minimumConfidenceForAlert` 0.5).
- **Enforcement modes only soften.** `ALERT_ONLY` reduces a `BAN` to an alert; `BAN_WAVE`
  converts a `BAN` to a `CANDIDATE`. A mode can never make enforcement easier than the evidence
  allows, so a misconfiguration cannot produce a ban the evidence would not support.

The statistical reasoning behind each of these is in
[`STATISTICAL_MODEL.md`](STATISTICAL_MODEL.md) §4–§6; the derivations in
[`MATHEMATICAL_MODEL.md`](MATHEMATICAL_MODEL.md) §4–§8.

---

## 8. Cross-cutting choices and known limitations

- **Immutability everywhere it can be had.** Domain types are `record`s; lists and maps are
  copied with `List.copyOf`/`Map.copyOf`/`Set.copyOf`; the components and engines are stateless
  and shareable across workers. This is the precondition for lock-free parallel analysis.
- **Validation in compact constructors.** Every record validates its invariants at construction
  (`OreProfile`, `EvidenceParameters`, `DecisionPolicy`, `ExposurePolicy`, `Observation`,
  `OreDiscovery`, …). A misconfiguration or a programming error fails at the boundary with a
  message naming the offending value, rather than corrupting a score silently.
- **Correctness in log space.** Evidence is accumulated as natural-log-odds, which turns
  Bayesian combination into addition and keeps the numbers finite for long sessions; the
  special functions (`SpecialFunctions`) all operate in log space for the same reason.
- **Storage is dialect-neutral.** The schema is written to execute unchanged on SQLite,
  MariaDB and PostgreSQL — see [`DATABASE.md`](DATABASE.md).

Known limitations the code itself documents, stated here so a reader is not surprised:

1. **Cumulative window versus bounded buffers.** A session's window is cumulative (each pass
   re-evaluates everything the session recorded), but the discovery buffer is bounded and
   discards its oldest entries, while `blocksMined` is an unbounded running scalar. On a session
   long enough to overflow the buffer, the buried-discovery count stops growing while the
   mined-block count does not, so the measured rate drifts *downward* and the assessment becomes
   progressively more lenient. The drift is bounded by `tracking.discovery-history-size` and in
   the safe direction, but it is real.
2. **The ledger's provenance assumption.** `BukkitWorldView.originAt` treats unattributed open
   space as `NATURAL_TERRAIN`; when the ledger has pruned an entry, or the block was broken
   before the plugin was installed, the error is toward leniency. Setting
   `analysis.exposure.excavation-attribution-required: true` reduces the confidence of such
   classifications.
3. **Automatic enforcement carries out the justified action.** `EnforcementService.accept(snapshot, outcome)`
   performs `kickPlayer` and records an `AUTOMATED_KICK` action when the outcome is `KICK`, and issues a
   ban and records `AUTOMATED_BAN` when it is `BAN`. (An earlier revision always kicked, even when the
   evidence justified a ban; the outcome is now passed through so the two cannot diverge.) Automated
   banning also happens through a ban wave (`executeWave`), and manually through commands and the GUI.
4. **Some GUI actions are placeholders.** In `GuiManager.dispatch`, the `INSPECT` and `FLAG`
   items have real effects: `FLAG` records a moderation event, `CLEAR` acknowledges the alert,
   `ADD_NOTE` records a note, and `TELEPORT` genuinely moves the moderator to the player. `KICK` and
   `BAN` open a confirmation menu whose confirm button executes the action (a distinct `CONFIRM`
   action — wiring the button back to the action's own name would have re-opened the menu forever).
   `TELEPORT_VANISH`, `SPECTATE` and `FREEZE` are **not implemented**: they report themselves as
   unavailable rather than appearing to succeed, because they need an external vanish/spectate/freeze
   plugin and a moderator who believed a player was frozen would stop watching them.
5. **Every key that was previously parsed-but-inert has been removed rather than left looking like a
   control.** `tracking.retain-disconnected-players`, `retention.ban-waves-days`,
   `debug.log-database`, `debug.log-paths`, `storage.sqlite.enable-wal` and
   `suspicion.minimum-independent-signals-for-ban` no longer exist in the shipped configuration; the
   sections above state what the code does instead. The per-ore `min-y`, `max-y`,
   `typical-vein-size` and `expected-distance-between-discoveries` remain parsed and validated but
   unread by any component, and that is the only remaining inert configuration. See
   [`CONFIGURATION.md`](CONFIGURATION.md) for the complete account.

See also: [`DATABASE.md`](DATABASE.md) (schema and migrations), [`PERFORMANCE.md`](PERFORMANCE.md)
(main-thread rule and budgets), [`CONFIGURATION.md`](CONFIGURATION.md) (every key),
[`PRIVACY.md`](PRIVACY.md) (what is stored about players), and
[`DEVELOPMENT.md`](DEVELOPMENT.md) (build, conventions and how to extend).
