# Development

This document covers how to build and test the project, the module layout, the coding
conventions the codebase actually follows, how to extend it (a new evidence component, ore,
migration, or Minecraft event), and the test strategy.

---

## 1. Toolchain and build

The project targets **Java 25** and is built with **Maven**. The compiler release is set once in
the parent `pom.xml` (`maven.compiler.release = 25`). The toolchain it is verified against is
Temurin 27, Maven 3.9.16, and `paper-api 26.2.build.129-stable`.

Targeting 25 while compiling on JDK 27 is deliberate: `javac --release 25` emits `major version 69`
class files and restricts the API to Java 25's, so the build fails rather than silently requiring a
newer runtime. The resulting jar therefore runs on any Java 25, 26 or 27 JVM, instead of demanding the
JDK it happens to be built with.

The code uses Java 21+ language and library features deliberately: `record`, `sealed` interfaces
and pattern `switch`, `List.getFirst()/getLast()`, and `Math.clamp`.

### Commands

```bash
# Compile and run all tests
mvn clean test

# Full build: tests plus the shaded plugin jar
mvn clean package

# Build only one module (and its dependencies)
mvn -pl xray-core test
mvn -pl xray-persistence test

# Run a single test class
mvn -pl xray-core test -Dtest=EvidenceEngineTest

# Run a single test method
mvn -pl xray-core test -Dtest=EvidenceEngineTest#caveExplorerIsNotSuspicious

# Run the persistence integration tests only
mvn -pl xray-persistence test -Dtest=PersistenceIntegrationTest
```

`mvn clean package` produces `xray-paper/target/xray-anticheat-1.0.0.jar`, a shaded jar that
bundles `xray-core`, `xray-persistence`, HikariCP and the three JDBC drivers. HikariCP is relocated
to `io.xrayac.libs.hikari` so it cannot clash with the server or another plugin. The shade plugin's
own ASM dependency is overridden to 9.10.1, which understands Java 25 class files (`major version
69`); this is configured in `xray-paper/pom.xml` and is why the build works without downgrading the
shade plugin.

**sqlite-jdbc is deliberately NOT relocated**, despite what an earlier revision of this file claimed.
It is a JNI library: the classes in `org.sqlite.core` bind to native methods whose symbol names are
derived from the original fully-qualified class names (`Java_org_sqlite_core_NativeDB_...`). Renaming
the Java side would leave the native side exporting the old symbols, and the driver would fail on the
first connection with `UnsatisfiedLinkError` — a failure invisible to unit tests, which run against
the unshaded modules, and visible only on a real server.

Surefire is configured with `useModulePath=false` in the parent, so tests run on the classpath
rather than the module path.

### Verifying the packaged jar

```bash
mvn clean package && bash tools/ci/verify_jar.sh
```

The unit and integration tests run against `target/classes` and the module classpath, so they cannot
see anything the shade plugin does. `tools/ci/verify_jar.sh` (invoked through `bash` — the executable bit is deliberately not
tracked, so it runs the same everywhere) inspects the finished jar and checks the
four things that would otherwise only fail on a real server:

1. every class the project compiles is `major version 69` (Java 25) — proves the release setting
   actually took effect;
2. the five configuration resources are present, so the plugin can write its defaults on first run;
3. all three JDBC drivers are still registered in `META-INF/services/java.sql.Driver` — if the shade
   merge dropped a registration, one database backend would fail at runtime only;
4. sqlite-jdbc was **not** relocated — relocating a JNI library breaks its native symbol names, and
   the `UnsatisfiedLinkError` appears only when a real connection is opened.

CI runs the same script, so it is also the local reproduction of a CI failure.

---

## 2. Module layout

```
xray-anticheat/
├── pom.xml                         parent: versions, modules, plugin management
├── docs/                           this documentation set
├── xray-core/                      pure Java analytical core (no Minecraft dependency)
│   └── src/main/java/io/xrayac/core/
│       ├── domain/                 Observation (sealed), PlayerRef, WorldId,
│       │                           VeinObservation, ExposureState, ExposureResult, MiningOrigin
│       ├── geom/                   Vector3, BlockPos, PrincipalAxes (PCA/Jacobi)
│       ├── world/                  ExposureAnalyzer, ExposurePolicy, VeinAnalyzer,
│       │                           BlockKind, MapOreCatalog
│       ├── analysis/               TrajectoryAnalysis, PlayerAnalysisWindow, OreDiscovery
│       ├── statistics/             LogOdds, LikelihoodRatios, the distributions,
│       │                           BinomialModel, SpecialFunctions
│       ├── evidence/               EvidenceEngine, EvidenceComponent, EvidenceContribution,
│       │   │                       EvidenceParameters, EvidenceStrength, SuspicionSnapshot
│       │   └── component/          the five components
│       ├── decision/               DecisionEngine, DecisionPolicy, ActionType,
│       │                           EnforcementMode, BanWavePlanner, BanWaveCandidate, BanWavePlan
│       ├── config/                 OreProfile, OreProfileRegistry, MapOreProfileRegistry
│       ├── port/                   WorldView, OreCatalog (outbound ports)
│       └── repository/             seven repository interfaces + PersistenceException
├── xray-persistence/               JDBC/HikariCP implementation
│   └── src/main/
│       ├── java/io/xrayac/persistence/
│       │   ├── DatabaseConfig, Dialect, ConnectionProvider, HikariConnectionProvider,
│       │   │   TransactionManager, PersistenceModule
│       │   ├── migration/MigrationRunner
│       │   └── jdbc/JdbcRepository + seven Jdbc*Repository
│       └── resources/migrations/   V1__initial_schema.sql, index.txt
└── xray-paper/                     Paper adapter
    └── src/main/
        ├── java/io/xrayac/paper/
        │   ├── XRayAntiCheatPlugin   composition root: wiring, scheduling, shutdown
        │   ├── adapter/              BukkitWorldView, ExcavationLedger, MaterialClassifier
        │   ├── alert/                AlertService
        │   ├── analysis/             AnalysisService
        │   ├── command/              XRayCommand
        │   ├── config/               ConfigLoader, PluginSettings
        │   ├── enforcement/          EnforcementService
        │   ├── gui/                  GuiManager
        │   ├── listener/             ObservationListener
        │   ├── message/              MessageService
        │   ├── persistence/          PersistenceBundle, PendingPersistence
        │   └── session/              PlayerSession, SessionRegistry
        └── resources/                config.yml, database.yml, messages.yml, gui.yml, plugin.yml
```

Dependency direction: `xray-core` depends only on `slf4j-api`; `xray-persistence` depends on
`xray-core` plus HikariCP and the JDBC drivers; `xray-paper` depends on both plus `paper-api`.
Nothing in `xray-core` may import a Bukkit type — that is the constraint that keeps the
mathematics testable without a server, and it is checked simply by not having the dependency
available to compile against.

---

## 3. Coding conventions

These are the conventions the codebase actually uses; follow them when extending it.

### Records for immutable value types

Nearly every domain type is a `record` with a **compact constructor that validates its
invariants**. Examples: `OreProfile` (rejects a multiplier ≤ 1, boundary probabilities, a
reversed Y range), `EvidenceParameters`, `DecisionPolicy` (refuses a ban band weaker than the
alert band, and a ban confidence floor below the alert floor), `ExposurePolicy`, `Observation`'s
variants, `OreDiscovery`, `Vector3`/`BlockPos`, and the nested configuration records in
`PluginSettings` (`Tracking`, `Performance`, `Retention`, `Debug`).

The rule: validate at the boundary and fail with a message naming the offending value, rather
than letting a bad number corrupt a score silently. References to mutable collections are
defensively copied in the constructor (`List.copyOf`, `Map.copyOf`, `Set.copyOf`), which is what
makes the objects safe to hand to another thread.

### Sealed interfaces for closed hierarchies

- `Observation` is `sealed` with `Movement`, `Orientation` and `Mining`. Consumers that `switch`
  on it are checked exhaustively by the compiler, so **adding a new observation type becomes a
  compile error at every site that must handle it** rather than a silently ignored case.
- `Distribution` is `sealed` permitting `PoissonDistribution`, `ExponentialDistribution` and
  `NormalDistribution`.

Use `sealed` wherever the set of variants is genuinely fixed and you want the compiler to police
it.

### Ports and adapters

The core declares the interfaces it needs from the outside world (`port/WorldView`,
`port/OreCatalog`, `config/OreProfileRegistry`, the seven `repository/*` interfaces) and depends
on those, never on Bukkit or JDBC. Adapters live outside the core: `MapOreCatalog`,
`MapOreProfileRegistry`, and the `Jdbc*Repository` classes; the Paper adapters are
`BukkitWorldView`, `ObservationListener`, `PersistenceBundle` and the service classes. When you
need something from the platform, add a port to the core and an adapter outside it — do not
import the platform type into the core.

### Thread ownership is explicit

Server-thread-confined classes say so and use unsynchronised collections on purpose
(`PlayerSession`, `SessionRegistry`, `AlertService`); worker-shared classes use concurrent
collections or immutability. The two bridges are `XRayAntiCheatPlugin.runAsync`/`runSync`. When
you add a class, decide which thread owns it and document the decision the way the existing
classes do.

### Statistics live in log space

All evidence arithmetic is done in natural-log-odds (`LogOdds`, `LikelihoodRatios`), and the
special functions (`SpecialFunctions`) operate in log space too. The reasons: independent evidence
combines by addition in log-odds, addition cannot underflow the way a running product of
probabilities can, and the intermediate quantities (binomial coefficients, Poisson masses)
overflow `double` long before the counts a busy server produces. Keep new statistical code in log
space unless there is a specific reason not to, and say why if you break the rule.

### Pure functions and failure isolation

Evidence components must be **pure functions of their arguments** and must **never throw on
plausible input**. Bad or absent data is reported as `EvidenceContribution.none(id, group,
reason)`. The engine also isolates failures: a component that throws is caught and recorded as a
non-informative contribution with the failure quoted (`EvidenceEngine.evaluateSafely`), so one
broken signal cannot deny a player an assessment. The same containment appears at the service
level: `AnalysisService.analyse` guards each player, `XRayAntiCheatPlugin.runAsync` logs a failed
background task rather than letting it escape the pool, and `ObservationListener` catches a
vein-analyser mismatch rather than interrupting collection for the whole server.

### Numbers are explained, not magic

Every parameter has a documented meaning and a defensible default (`EvidenceParameters`,
`OreProfile`, `DecisionPolicy`). If you introduce a constant, document where it comes from. The
project's preferred style is a geometric or statistical derivation where one exists (the `0.067`
look baseline is the exact solid-angle fraction of a 30° cone) and an explicit statement of
"modelling choice, not derivation" where one does not.

---

## 4. Adding a new evidence component

An evidence component is one implementation of `EvidenceComponent` plus one registration.

1. **Implement the interface** in `xray-core/.../evidence/component/`:

   ```java
   public final class MyComponent implements EvidenceComponent {
       public static final String ID = "my-signal";
       public static final String GROUP = "my-group";
       private static final double RELIABILITY = 0.6;

       @Override public String id() { return ID; }
       @Override public String independentGroup() { return GROUP; }

       @Override
       public EvidenceContribution evaluate(PlayerAnalysisWindow window,
                                             OreProfileRegistry profiles,
                                             EvidenceParameters parameters) {
           // pure computation over window/profiles/parameters only
           // return EvidenceContribution.none(ID, GROUP, "reason") when silent
       }
   }
   ```

2. **Choose `independentGroup` carefully.** Components measuring the *same* underlying signal
   must share a group (see `HiddenDiscoveryRateComponent` and `InterDiscoveryWaitingComponent`,
   both `discovery-rate`; `OreTargetingComponent`'s movement and look measurements are one
   `targeting` group). A genuinely new signal gets its own group. This choice is what the engine's
   `minimumIndependentGroups` gate counts, so mis-grouping either manufactures or suppresses
   corroboration.

3. **Return a valid `EvidenceContribution`.** Its compact constructor enforces: a non-blank
   `componentId` and `independentGroup`, a **finite** `logLikelihoodRatio` (clamp your own output
   — a non-finite value would poison the sum), a non-negative `sampleSize`, a `reliability` in
   `[0, 1]`, and a non-blank `explanation`. Set `sampleSize` to the number of **independent**
   observations (one per vein, never per block), and set the explanation to a sentence a moderator
   can check. Add metrics with `EvidenceContribution.metrics("name", value, ...)` for the live GUI.

4. **Use the existing helpers.** `LikelihoodRatios.poissonCount`, `.binomialCount`,
   `.exponentialIntervals` cover the models the engine uses; build on them rather than re-deriving
   log-ratios, so the assumptions stay consistent.

5. **Register it in the composition root.** Components are supplied to `new EvidenceEngine(List.of(...))`
   in `XRayAntiCheatPlugin.onEnable`. Add your component to that list. There is no
   auto-discovery — the list is explicit by design, so the set of signals a server runs is visible
   in one place. The `EvidenceEngineTest` fixture constructs the same list; add it there too so
   the end-to-end tests exercise it.

6. **Test it.** Add unit tests against constructed windows. The existing `EvidenceEngineTest`
   shows the pattern: build a `PlayerAnalysisWindow` with synthetic `OreDiscovery`s and assert on
   the verdict band, the independence of the groups, and the presence of recorded exculpatory
   evidence, rather than on intermediate numbers.

---

## 5. Adding a new ore

Two ways, depending on whether the ore is a project default or a server addition.

### As a server configuration addition (normal case)

Add an `ores.<id>:` section to `config.yml` with at least: `enabled`, `display-name`,
`block-keys` (the namespaced material keys, including both stone and deepslate variants if they
exist), `hidden-discovery-rate-per-1000-blocks`, `ore-informed-rate-multiplier`, and the alignment
probabilities. `ConfigLoader.loadOre` will read it and `PluginSettings.oreCatalog()` will map its
blocks.

- A key matching a built-in profile (`diamond`, `emerald`, `ancient_debris`) inherits that
  profile's values for anything left unset.
- A genuinely new ore **must specify its own numbers**; if a required value is missing or invalid
  it is disabled with a warning rather than guessed at, because inventing a plausible baseline
  would silently become the yardstick by which somebody is judged.
- The `OreProfile` constructor enforces: rate `> 0`, multiplier `> 1`, `min-y ≤ max-y`,
  `typical-vein-size ≥ 1`, threshold in `(0, 90)`, alignment probabilities strictly in `(0, 1)`
  with the informed value above the legitimate one, and `evidence-weight` in `(0, 1]`.
- `config.yml` ships a disabled `nether_gold` entry as a worked example of the shape.

### As a shipped default (rare)

Add a profile to `MapOreProfileRegistry.defaults()` in the same shape as the existing three, and
add the mapping. This makes the ore analysed out of the box. Do this only if the ore is common
enough to justify a shipped prior, and document the reasoning for its numbers the way the existing
three are documented.

Whenever an ore is added, remember that vein reconstruction merges the *variants* of one ore
(`minecraft:diamond_ore` and `minecraft:deepslate_diamond_ore`) but never merges different ores
(see `VeinAnalyzerTest.differentOreNotMerged`).

---

## 6. Adding a migration

Full detail is in [`DATABASE.md`](DATABASE.md) §4. In brief:

1. Create `xray-persistence/src/main/resources/migrations/V<n>__<description>.sql` with the next
   integer version.
2. Write **additive** DDL only — `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, or
   `ALTER TABLE … ADD COLUMN … DEFAULT …`. Never drop or destructively alter data.
3. Add the file name to `migrations/index.txt` on its own line.
4. Do not put a semicolon inside a string literal in a migration (the splitter is deliberately
   simple).
5. Update the repository if the change is used by one, and extend
   `PersistenceIntegrationTest.schemaIsComplete` with any new table so the build fails if the
   migration did not create it.

Migrations are applied exactly once, atomically per migration, and re-running them is a no-op
(`migrationsApplyOnce` tests this). They run from `PersistenceBundle.connect` (and again during
recovery) when `storage.migrations.run-on-startup` is true.

---

## 7. Extending the plugin with a new Minecraft event

The analytical core is deliberately decoupled from Minecraft events: it knows only the
`Observation` hierarchy. Adding a new event is therefore a two-part change, and the first part is
compiler-checked.

1. **Add a variant to the sealed `Observation` interface** (`xray-core/.../domain/Observation.java`).
   Every variant carries `PlayerRef`, `WorldId`, `long tick` and `Instant timestamp`, and validates
   itself in a compact constructor. Because `Observation` is sealed, adding a variant causes a
   **compile error at every `switch` over it**, which is the point: you cannot forget a consumer.

   ```java
   record ContainerOpened(
           PlayerRef player, WorldId world, long tick, Instant timestamp,
           BlockPos pos) implements Observation {
       public ContainerOpened {
           if (pos == null) throw new IllegalArgumentException("a position is required");
       }
   }
   ```

2. **Translate the server event into the variant in the adapter**, in
   `io.xrayac.paper.listener.ObservationListener`. Register a handler with
   `@EventHandler(priority = EventPriority.MONITOR)`, check `settings.get().analysisEnabled()`
   and `analysesWorld(...)` early, build the immutable observation on the server thread, and
   enqueue anything to persist (`PendingPersistence`). Never call a repository or the evidence
   engine from a listener. The existing handlers (`onBlockBreak`, `onPlayerMove`, `onJoin`,
   `onQuit`, `onWorldChange`) are the template; `ignoreCancelled = true` is used wherever a
   cancelled event means the action did not happen.

3. **Make the core use it.** If the new observation should feed an existing analysis, consume it
   in the appropriate layer (`PlayerSession` for building a window, exposure/vein reconstruction
   for mining, and so on). Add a unit test at that layer, using `FakeWorldView` or a constructed
   window as appropriate.

4. **Add a buffer bound** to `PluginSettings.Tracking` / `config.yml` only if the event needs a
   new bounded buffer or sampling rule; validate it in the record's compact constructor.

---

## 8. Test strategy

The suite is split by module, and the split is deliberate.

### Unit tests (`xray-core`, 77 tests)

These run in a plain JVM with no Minecraft server and no database. They are the specification of
the mathematics and the judgement.

| Class | Tests | Covers |
| --- | --- | --- |
| `StatisticalPrimitivesTest` | 32 (29 tests + 1 parameterized over 3 confidence levels) | closed-form checks of `SpecialFunctions`, `LogOdds`, the distributions, the likelihood ratios, `BinomialModel` (p-values and both confidence intervals) — validated against analytically known values, not recorded snapshots |
| `EvidenceEngineTest` | 10 | end-to-end scenarios: cave explorer not flagged (and exculpatory evidence recorded), ore-vision targeter flagged on multiple independent groups, lucky small-sample player not convictable, single-signal restraint, strip-miner not flagged by geometry, tunnel-follower not flagged, decay, explainability, `UNKNOWN`-is-not-leniency |
| `DecisionEngineTest` | 15 | evidence-to-action mapping, enforcement modes only ever softening, ban-wave interval/threshold/expiry/merge rules, policy validation |
| `ExposureAnalyzerTest` | 12 | hidden/enclosed ore, strip-miner's fresh approach, natural cavity exposure, glass (transparency), diagonal vision on/off, other players' and own older tunnels, unloaded-chunk `UNKNOWN` |
| `VeinAnalyzerTest` | 7 | 26-connectivity, variant merging, no cross-ore merging, size bound and truncation, shape/dominant axis, non-ore seed rejection |
| `ToolchainSmokeTest` | 1 | the core module is reachable |

The support type for world-based tests is `FakeWorldView` (`xray-core/src/test`), an in-memory
`WorldView` that defaults to solid, natural, unexcavated stone and lets each test carve out one
scenario (`naturalCavity`, `playerExcavated`, `otherPlayerExcavated`, `unattributedAir`,
`unload`, `withDefaultKind`). This is what makes the exposure and vein tests readable
descriptions of gameplay rather than walls of setup.

Where possible the expected value is a **closed form** the test can reason out (e.g. the two-sided
p-value of 12 successes under p = 0.5 is `2·0.5¹²`), so a test cannot pass merely because it
remembers a buggy answer.

### Integration tests (`xray-persistence`, 7 tests)

`PersistenceIntegrationTest` runs against a **real embedded SQLite database file** created by a
`@TempDir` and the real `MigrationRunner`/`HikariConnectionProvider`. They exist because compiling
is not the same as working: the unit tests would pass while the schema had a syntax error or a
repository's bind order did not match its table. The seven tests cover: migrations apply once and
are recorded; every expected table was created; player round-trip and upsert preserves first-seen;
ledger provenance round-trip and unrecorded-position semantics; atomic snapshot-plus-evidence
round-trip; candidate strengthening preserves the peak and first-detection; retention pruning.

**SQLite is the only engine covered by tests.** MariaDB and PostgreSQL share the same code paths
and the same dialect-neutral schema, but verifying them needs a live server and is left to
deployment. This is stated in the test's own Javadoc and in [`DATABASE.md`](DATABASE.md) §0.

### Not tested

`xray-paper` has **no tests**: `ConfigLoader`, `PluginSettings`, `MessageService`, `GuiManager`,
`AnalysisService`, `ObservationListener`, `AlertService`, `EnforcementService`, the session and
ledger classes and `XRayAntiCheatPlugin` are exercised only by running the plugin on a server.
This is the clearest gap in the suite, and adding a `ConfigLoaderTest` (loading a
`YamlConfiguration` and asserting the fallback-per-key behaviour and the two fatal conditions)
would be a high-value first contribution, as it needs no running server.

### Running a single test

```bash
mvn -pl xray-persistence test -Dtest=PersistenceIntegrationTest
mvn -pl xray-core test -Dtest=EvidenceEngineTest#targeterIsFlagged
```

Test classes are named `*Test` and are discovered by Surefire's default includes. Prefer JUnit 5
(`org.junit.jupiter`) with AssertJ assertions, and use `@Nested`/`@DisplayName` to organise a class
by the behaviour it specifies — the existing tests read as a list of claims about how particular
kinds of player must be treated.

### The guiding principle

Tests are written in terms of the decisions a moderator would actually take — the verdict band,
the independence of the signals, the presence of recorded exculpatory evidence — and **not** in
terms of intermediate numbers. If a future change makes the engine flag cave explorers, or lets
three lucky finds reach a decision, or lets one strong signal family carry a verdict, the tests
fail. Keep that property: it is what makes the suite a specification rather than a regression net.
