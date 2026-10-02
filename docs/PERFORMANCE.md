# Performance

The overriding constraint on this project is that it runs inside a Minecraft server, where the
server thread has 50 ms per tick and every millisecond spent there is a millisecond not spent
simulating the world. Everything in this document follows from that.

The design is implemented across all three modules. Where a figure is an estimate rather than a
measurement, this document says so; no timing harness is shipped, so the per-event and
per-pass numbers below are the design budget, not benchmark output.

---

## 1. The absolute main-thread rule

> **The Minecraft server thread only collects lightweight observations and renders the
> interface. It never performs blocking database work, and it never runs the statistical
> analysis.**

This is not a guideline; it is written into the interfaces and enforced by the code structure:

- `port/WorldView` — "Implementations are **not** required to be thread-safe. All calls happen
  on the server thread, during observation collection … Implementations must never block on
  I/O."
- `repository/PlayerRepository` (and its six siblings) — "Every method here performs blocking
  I/O and must therefore **never** be called from the Minecraft server thread."
- `persistence/ConnectionProvider.acquire()` — "blocks until a connection is available … so it
  must never be called on the Minecraft server thread."

How the rule is enforced:

- **By the type system.** `xray-core` holds no `World` and no `Connection`, so a component
  physically cannot load a chunk or open a transaction. The only way to touch world state is
  through `WorldView`, whose contract confines it to the server thread; the only way to touch
  storage is through a repository, whose contract forbids it.
- **By data ownership.** The server thread freezes observations into immutable
  `PlayerAnalysisWindow`/`SuspicionSnapshot` records (`List.copyOf` in their compact
  constructors) and hands those to a worker. `PlayerSession`/`SessionRegistry` are
  server-thread-confined and deliberately unsynchronised, because no worker ever touches them.
- **By the two bridges.** `XRayAntiCheatPlugin.runAsync` submits to the worker pool;
  `runSync` schedules a task back onto the server thread. Every server-side effect on the
  analysis path (alert, enforcement, GUI) goes through one of these.
- **By scheduling choice.** The flush task, the retention task and the recovery task are all
  registered with `runTaskTimerAsynchronously`; only observation collection, session pruning,
  periodic analysis submission and the automatic wave run on the server thread.
- **By the read guard.** `BukkitWorldView.isLoaded` checks `World#isChunkLoaded` before any
  block read, so a block break in an unloaded chunk can never trigger a synchronous chunk load.

---

## 2. The worker pool

Statistical analysis and database I/O run on a single `ExecutorService` created in
`XRayAntiCheatPlugin.createWorkerPool`:

| Key | Default | Meaning |
| --- | --- | --- |
| `performance.analysis-threads` | 4 | size of the fixed platform pool (ignored when virtual threads are on) |
| `performance.use-virtual-threads` | true | use `Executors.newVirtualThreadPerTaskExecutor()` instead of a fixed pool |

With virtual threads on (the default), every submitted task gets its own virtual thread: a
worker parked on a JDBC call does not occupy an OS thread, so the pool scales to the blocking
work almost for free. With virtual threads off, a fixed pool of `analysis-threads` is used,
with daemon threads named `xray-anticheat-worker`. The work is blocking-I/O-heavy with light
CPU, which is exactly the case virtual threads are for.

`runAsync` wraps each task's body so that a runtime failure is logged rather than silently
swallowed by the pool.

---

## 3. Bounded per-player buffers

Per-player tracking must not grow without limit. `PlayerSession` holds three bounded deques and
discards the oldest entry when a buffer overflows (`trimTo`). The bounds come from
`PluginSettings.Tracking`, which `SessionRegistry` passes to each session it creates.

| Key | Default | What it bounds |
| --- | --- | --- |
| `tracking.movement-sample-distance` | 0.5 blocks | minimum movement before a new path point is stored |
| `tracking.path-buffer-size` | 512 | path points retained per player |
| `tracking.mining-buffer-size` | 2048 | recent block breaks retained per player |
| `tracking.discovery-history-size` | 200 | recent ore discoveries retained per player |
| `tracking.analysis-interval-minutes` | 5 | longest a tracked player goes without an assessment |
| `tracking.session-idle-timeout-minutes` | 30 | idle time before an online-but-idle player's session is pruned |
| — | — | a `retain-disconnected-players` key was listed here and has been **removed**: there is no such choice. A departing player's session is always finalised and removed, because no further events can arrive for them and the buffers would be pure memory waste. |

Two points of detail, both from the code:

- **`movement-sample-distance` is the main cost lever, and distance is still accumulated
  below it.** `PlayerSession.recordMovement` adds every move event's delta to
  `distanceTravelled` (the denominator of several rates) but stores a path point only once the
  player has moved at least the threshold from the last stored point. So a player jittering
  under the threshold still accrues real travelled distance while the path buffer stays small
  enough to hold many minutes of motion. The `ObservationListener.onPlayerMove` handler also
  ignores pure head rotation (compared by block coordinate) before doing any work.
- **Periodic analysis is exculpatory work, not overhead for its own sake.** A player who mined
  a great deal and found nothing unusual produces strongly *negative* evidence, which is real
  evidence in their favour. `SessionRegistry.pruneIdle` finalises idle-but-present players, and
  `ObservationListener.onQuit`/`onWorldChange` finalise departing players, so the exculpatory
  pass happens even without the periodic timer.

**Disconnected players are always released.** `ObservationListener.onQuit` and `onWorldChange`
finalise a departing player's session — analysing it so that a cheater who logs off mid-rampage is
still assessed — and then remove it. There is no option to retain it, and no key claiming otherwise.

---

## 4. Batching and flush strategy

Writes are batched in two places, both on workers.

| Key | Default | Meaning |
| --- | --- | --- |
| `performance.persistence-batch-size` | 500 | rows per JDBC batch on bulk writes |
| `performance.flush-interval-seconds` | 10 | how often queued observations are flushed |
| `storage.pool.batch-size` | 500 | rows per JDBC batch (database.yml) |

The mechanism, concretely:

- **The server thread only enqueues.** `ObservationListener` calls `PendingPersistence.enqueueMining`
  / `enqueueDiscovery` (a `ConcurrentLinkedQueue` offer — nanoseconds, never blocks) and
  `ExcavationLedger.record` (one map insert plus one queue offer).
- **A scheduled worker drains and writes.** `XRayAntiCheatPlugin.flushPendingObservations` and
  `flushExcavationLedger` run on `runTaskTimerAsynchronously` every `flush-interval-seconds`.
  They drain up to `persistence-batch-size` items and call
  `MiningEventRepository.saveAll`, `OreDiscoveryRepository.save` (per row) and
  `WorldModificationRepository.recordAll`.
- **The repositories batch internally.** `JdbcMiningEventRepository.saveAll` and
  `JdbcWorldModificationRepository.recordAll` accumulate rows with `PreparedStatement.addBatch()`
  and call `executeBatch()` via `JdbcRepository.flushIfFull(stmt, pending, batchSize)`, flushing
  a partial batch at the end. This amortises connection acquisition and statement preparation
  across many rows.
- **Snapshots are atomic.** `JdbcSuspicionRepository.save` writes the snapshot row and all its
  evidence rows in one transaction; `TransactionManager` saves and restores the connection's
  auto-commit state, so a pooled connection returned in the wrong mode cannot corrupt an
  unrelated later operation.
- **The pool caches statements.** `HikariConnectionProvider` enables `cachePrepStmts`,
  `prepStmtCacheSize=250` and `prepStmtCacheSqlLimit=2048` on the server engines — the highest
  leverage JDBC knob here, because the same dozen statements run on every batch.

**The in-memory queues are bounded, with hard-coded limits.** `PendingPersistence` is
constructed with `maxQueued = 1,000,000` in `XRayAntiCheatPlugin` (not a configuration key),
and each queue discards its oldest entry when full, counting the drop. `ExcavationLedger`'s
pending-write queue has a safety valve at 5,000,000 entries. Drops are counted and surfaced
(`PendingPersistence.droppedMiningCount`/`droppedDiscoveryCount`), so a storage outage is
visible rather than silent. On a failed write the drained batch is recorded as dropped and the
store is marked unavailable; recovery is attempted by a timer.

---

## 5. The in-memory excavation ledger and its memory bound

The exposure analyser must answer "who removed the block that used to be here, and when?" *on
the server thread*, without a database round trip — a lookup from that context would be a
blocking round trip on the main thread. `ExcavationLedger` therefore keeps a bounded, in-memory
copy of recent provenance; the database is a durability record written asynchronously and
reloaded on startup (through `WorldModificationRepository`).

| Key | Default | Meaning |
| --- | --- | --- |
| `performance.ledger-max-entries` | 2,000,000 | maximum entries retained in memory |
| `performance.ledger-retention-hours` | 72 | hours of provenance to keep (pruned by `pruneSessions`) |

**How the bound is implemented.** `ExcavationLedger` wraps a `LinkedHashMap` subclass
(`BoundedMap`) whose `removeEldestEntry` discards the oldest insertion once `size() >
maxEntries`; insertion order is the right eviction order, because the entries that matter most
(the player's own *recent* approach excavation) are the newest. All map access is synchronised;
the pending-write queue is a `ConcurrentLinkedQueue`, so enqueuing from the server thread never
contends with the flush worker.

**Worked memory estimate.** The `config.yml` comment states "roughly 100 bytes per entry",
giving `2,000,000 × 100 B = 200 MB`. Being precise about where the bytes go, an entry is a
`LedgerKey` record (`String worldKey` + three `int`s) mapped to an `Entry` record (`MiningOrigin`
enum reference, `UUID actorId`, `long removedAtEpochMillis`), inside a `LinkedHashMap` and a
`synchronizedMap` wrapper whose per-node overhead is significant:

| Component | Approximate size |
| --- | --- |
| `LedgerKey` (object header + `String` ref + 3 `int`s) | ~32 B |
| `worldKey` `String` (~20 chars) | ~60 B (object header + backing array) |
| `Entry` (header + enum ref + `UUID` ref + `long`) | ~32 B |
| `UUID` (two `long`s) | ~24–32 B |
| `HashMap` node + bucket amortised | ~40–48 B |
| **Total** | **~190–210 B/entry** |

So the honest statement is: two million entries is on the order of **200–400 MB** resident,
depending on the JVM's object layout, not a flat 200 MB. Lower `ledger-max-entries` on a
memory-constrained server; the only consequence is that older provenance is forgotten, so the
analyser reports `UNKNOWN` (neutral) or `NATURAL_TERRAIN` (lenient) more often rather than
guessing — the safe direction. Watch the actual footprint under a profiler before trusting any
single figure.

---

## 6. Why exposure analysis stays on the server thread but statistics do not

This asymmetry is the single most important performance decision in the design.

**Exposure analysis reads blocks, so it must stay on the server thread or not happen at all.**
`ExposureAnalyzer.analyze` and `VeinAnalyzer.analyze` consult `WorldView.kindAt`,
`blockKeyAt`, `originAt`, `removalEpochMillis` and `isLoaded`. Asking a Bukkit world for a block
in an unloaded chunk either loads the chunk (a performance disaster) or returns a default that
looks like solid stone — which would fabricate "hidden ore" evidence. So the read happens during
collection, in `ObservationListener`, and only plain values (`ExposureState` and its rationale,
an `OreDiscovery`) leave the thread.

**Statistical analysis touches no server state, so it runs on a worker.** Once the observations
are frozen into a `PlayerAnalysisWindow`, `EvidenceEngine.evaluate` is pure CPU over plain
records — a PCA of up to 512 path points, five components each iterating the discovery list, and
the special-function evaluations for the binomial/Poisson ratios. Moving it off-thread keeps
that work out of the tick budget entirely.

---

## 7. Expected cost per block break and per analysis pass

These are **estimates from the code's structure, not benchmarks**. No timing harness exists in
the repository.

### Per block break (server thread, `ObservationListener.onBlockBreak`)

1. World-name and `analysisEnabled` checks; get or create the `PlayerSession`.
2. `MaterialClassifier.blockKey(block.getType())` — one enum/key lookup.
3. If and only if the block is a configured ore (a hash-map lookup in `MapOreCatalog`):
   - `VeinAnalyzer.analyze` walks connected ore blocks up to `max-vein-size` (default 64). Each
     visited block costs one `ExposureAnalyzer.analyze`, which inspects six orthogonal
     neighbours (and, only when `diagonal-visibility` or `occupied-space-radius > 1` is
     configured, a bounded vantage-point search with a line-of-sight sample every 0.2 blocks).
   - A separate `ExposureAnalyzer.analyze` for the broken block itself.
   - `TrajectoryAnalysis.approach` over the session's path points.
4. `ExcavationLedger.record` — one map insert plus one queue offer.
5. `session.recordMining` — counter increments plus one bounded-deque append.
6. `PendingPersistence.enqueueMining` — one queue offer.

For an ordinary (non-ore) block the cost is steps 1, 2, 4, 5, 6: constant-time and small. The
dominant term is step 3, and it is bounded by `max-vein-size` and the vantage radius, so a
pathological blob cannot turn one break into an unbounded scan. A vanilla diamond vein is 1–8
blocks and ancient debris 1–3, so in practice a vein walk is a handful of blocks, not 64.

### Per movement event (server thread, `ObservationListener.onPlayerMove`)

Pure head rotation is rejected by comparing block coordinates (no allocation). A genuine move
updates `lastKnownPosition`/`distanceTravelled` and, only if past the sample threshold, appends
one path point. Constant time.

### Per analysis pass (worker thread, `AnalysisService.analyse`)

- One history hydration, which is **two indexed reads** — `ore_discoveries` and `mining_events`, both
  keyed on player and time — and only when the cached copy has aged past
  `analysis.history.refresh-minutes`. Between refreshes a pass performs no history I/O at all. The cache
  holds one entry per player and world, bounded at 512 entries with opportunistic expiry.
- Five components, each a linear scan of the window's discovery list, bounded by
  `analysis.history.max-discoveries` (2000) plus the session's own discoveries.
- One `EvidenceEngine.evaluate` (grouping, shrinkage, decay) — linear in the number of
  contributions.
- A handful of `log-gamma`/incomplete-beta evaluations (`SpecialFunctions`).
- One `DecisionEngine.decide`, then one batched transaction to persist the snapshot and one
  insert/update if the player became a candidate.

None of this is quadratic in the observation count, so a pass is dominated by the constant
factors and the database round trip, not by data volume. The PCA (`PrincipalAxes.of`, Jacobi on
a 3×3 matrix) is O(path points) and runs once per assessment, derived from the window's trajectory on
demand.

The one cost that scales with configuration rather than with the player is the merged trajectory: it is
the stored mining path (up to `analysis.history.max-mining-events`) concatenated with the session's
movement samples (up to `tracking.path-buffer-size`), so raising both limits raises the work in the PCA
and in the geometry metrics together. The defaults keep a merged path in the low tens of thousands of
points, which the decomposition handles in single-digit milliseconds.

---

## 8. Database outage: degraded mode

The design's stance is that **a Minecraft server must come up even if its database does not.**

- `HikariConnectionProvider` is created with `initializationFailTimeout = -1`, so construction
  does not fail on an unreachable database; the first `acquire()` reports the problem.
- `PersistenceBundle` holds a volatile `available` flag. `markUnavailable()` flips it after a
  failed operation; the rest of the plugin checks `isAvailable()` before attempting a write.
- `database.yml → storage.fail-safe` controls the rest:

| Key | Default | Meaning |
| --- | --- | --- |
| `storage.fail-safe.continue-without-database` | true | an unreachable database does not prevent the plugin enabling |
| `storage.fail-safe.retry-interval-seconds` | 30 | seconds between reconnection attempts (`ConfigLoader` floors this at 5) |

With `continue-without-database: true` (the default), `XRayAntiCheatPlugin.connectPersistence`
catches the `SQLException`, logs clearly, builds a bundle via `PersistenceBundle.unavailable`,
and continues with **reduced function**:

- Observation collection and analysis continue **in memory**: assessments still happen and
  alerts still fire.
- **Nothing is persisted.** `AnalysisService.persist` and the flush methods no-op while
  unavailable. Buffered observations are drained and, if a write fails, counted as dropped
  (`errors.persistence-dropped` exists to report exactly this) — the evidence model under-counts
  affected players until storage recovers, stated rather than hidden.
- **Ban-wave enforcement is suspended**, because a wave is meant to recompute each candidate's
  evidence from stored data before anyone is removed.
- On recovery, `attemptDatabaseRecovery` runs migrations if needed, proves the connection with a
  `SELECT 1` round trip (a pool created but never used proves nothing), flips `available`, and
  the plugin reloads stored candidates and logs `database-recovered`.

With `continue-without-database: false`, a failed connection disables the plugin instead.

---

## 9. The knobs an administrator can turn

Ordered by how much they matter.

| Knob | Default | Effect of changing it |
| --- | --- | --- |
| `tracking.movement-sample-distance` | 0.5 | the single biggest lever on per-event cost; raise to cheapen collection |
| `performance.analysis-threads` | 4 | platform-pool size when virtual threads are off |
| `performance.use-virtual-threads` | true | turn off only if virtual threads are undesirable |
| `tracking.path-buffer-size` | 512 | linear memory and PCA cost per player |
| `tracking.mining-buffer-size` | 2048 | linear memory per player |
| `tracking.discovery-history-size` | 200 | linear memory and component-loop cost per player |
| `performance.ledger-max-entries` | 2,000,000 | the largest single memory consumer (§5) |
| `performance.ledger-retention-hours` | 72 | shorter = less memory, more `UNKNOWN` provenance |
| `performance.max-vein-size` | 64 | caps the per-ore-break world scan on the server thread |
| `performance.flush-interval-seconds` | 10 | shorter = fresher persistence, more DB round trips |
| `performance.persistence-batch-size` | 500 | larger = better throughput, more memory, later flush |
| `storage.pool.maximum-pool-size` | 10 (remote) / 4 (SQLite) | more concurrent writers; on SQLite a larger pool is counterproductive |
| `tracking.analysis-interval-minutes` | 5 | how often an active player is assessed on the timer |
| `tracking.session-idle-timeout-minutes` | 30 | frees session buffers for online-but-idle players |
| `storage.fail-safe.retry-interval-seconds` | 30 | how often recovery is attempted during an outage |
| `debug.log-analysis` | false | logs a full explanation per assessment; useful for calibration, costly in production |
| `retention.*` | 7/90/180/30/365 days | less data = smaller queries and faster prunes |

There is no configurable bound for the `PendingPersistence` queues (hard-coded 1,000,000) or the
ledger pending-write valve (5,000,000); they are compile-time constants.

---

## 10. What to measure if it feels slow

Work down this list; it is ordered by likelihood and by how directly each item can be measured.

1. **Is the server thread actually spending time in the plugin?** Use `spark` or
   `async-profiler` with the server-thread view. The plugin should appear as collection-only
   work there; if statistics or JDBC show up, the threading contract is being violated.
2. **Check `debug.enabled` and `debug.log-analysis`.** Per-assessment logging is off by default
   and must stay off in production.
3. **Check the pool statistics.** `/xray status` reports them (`PersistenceBundle.poolStatistics`
   → `HikariConnectionProvider.poolStatistics`, active/idle/waiting/total). A nonzero *waiting*
   count means workers are queuing on connections: raise `maximum-pool-size`, or find the slow
   query.
4. **Check for a slow query.** The retention prune (large `DELETE`s) is the usual culprit on a
   big installation; it runs only during `retention.prune-hour`, so schedule that for a quiet
   hour or shorten `retention.*`. The plugin has no statement-level logging, so use the database
   server's own slow query log.
5. **Check the ledger.** `/xray status` reports the in-memory ledger size. If the heap is under
   pressure, lower `ledger-max-entries`/`ledger-retention-hours` first — it is the one structure
   whose bound is measured in hundreds of MB.
6. **Check GC and allocation.** If the profiler shows allocation-bound server-thread time, look
   at `movement-sample-distance` first: it drives how many path points and observations are
   created per player.
7. **Check the vein size bound.** A `max-vein-size` far larger than any real vein does not help
   detection and only widens the worst case on the server thread.

Measure before tuning. Every knob trades detection quality or fidelity for speed, and the
defaults are chosen so that the honest answer to "is it slow?" starts from a configuration that
is not the cause.
