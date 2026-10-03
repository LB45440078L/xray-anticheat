# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2026-10-03

A release in two halves: the plugin now targets the **Spigot API only** instead of Paper, and it can serve
its own **administration web panel** - evidence review, moderation and the audit trail in a browser, with
nothing else to install and no dependency added.

It also carries corrections reported from live play: the moderator interface mishandled clicks,
`config.yml` was never written to disk, and a legitimate player mining veins that were open at one end
could be scored as if they had found buried ore.

### Fixed

- **The interface could not be used, and could be used to take items.** Open menus were tracked in a
  per-player map, so opening a second menu from the first deleted the state just written for the new
  one: clicks did nothing. The same handler also returned before cancelling when it found no state, so
  an item could simply be taken, and drag events were not handled at all. Menus now carry an
  `InventoryHolder` that identifies them without any lookup that can go stale, and clicks and drags
  involving a menu are cancelled before anything else happens.
- **Player heads did not paginate.** The list now lays entries out above a configurable navigation row
  and shows previous / page-counter / next, the arrows appearing only when a page exists that way.
- **`config.yml` was never written.** `getConfig()` only reads; nothing called `saveDefaultConfig()`, so
  the file was absent and every setting silently fell back to its built-in default. The resource export
  was never at fault — all five resource files were present in the jar throughout.
- **A vein open at one end was scored as buried ore.** A discovery was classified by the single block
  the player happened to break first, so entering a vein from the side, or from the neighbouring tunnel
  they were digging, recorded it as hidden even though the vein was open to a cave. A vein is now judged
  by its most visible member (`ExposureState.mostVisible` via `VeinObservation.aggregateExposure`).
  Measured on the regression scenario, twenty such veins scored 0.997 (**STRONG**) before the fix and
  ~1.6e-11 after.
- **One vein produced one discovery per block.** The later blocks were biased towards `HIDDEN`, because
  by then the player's own earlier breaks were the fresh openings beside them. Every block of a recorded
  vein is now accounted for (`PlayerSession.accountVein`), so a vein contributes one judgement; the set
  is bounded so a long session cannot retain every vein it ever saw.
- **`/xray reload` did not reload the interface or the messages.** `GuiManager` held the previous
  `gui.yml` object, and `AlertService`/`EnforcementService` held the previous `MessageService`, so a
  corrected string applied everywhere except where it was needed. Both are now read live.
- **`TELEPORT_VANISH` claimed the moderator was vanished.** It teleported and said "(vanished)" while
  doing nothing of the kind, which would leave a moderator standing in plain sight believing they were
  hidden. It now teleports and states that vanish needs an external plugin.
- **`CLEAR` and `ADD_NOTE` reported work they had not done.** "Acknowledge alert" had nothing to
  acknowledge; it now clears the alert throttle for that player, which is real and useful. "Add note"
  claimed a note was attached without one; it now points at `/xray note <player> <text>`.
- **`statistics` was a menu that did not exist.** `gui.yml` shipped a `statistics` section and the player
  detail page pointed an item at `OPEN:statistics`, but the action was silently ignored, so the item did
  nothing. The per-ore breakdown is now implemented, with per-ore icons, and an unrecognised menu or
  action name is logged rather than ignored.
- **Inert configuration.** `common.back`, `common.close`, `player-list.sort`,
  `player-list.player-item.action` and `config-version` were shipped but read by nothing — exactly the
  "I changed it and nothing happened" trap. The dead keys are gone, and `config-version` is now
  validated: a file written by a newer release than the running build understands is reported instead of
  being ignored in silence.

### Added

- `XRayMenu`, the inventory holder carrying a menu's identity, page and pending confirmation.
- `ExposureState.mostVisible(Collection)`, `VeinObservation.aggregateExposure()`, `isVisibleByOrdinaryPlay()`.
- `PlayerSession.isVeinAccounted`/`accountVein`, bounded by the mining buffer.
- `gui.yml`: `player-list.navigation`, `statistics.back-slot`, `statistics.back`, `statistics.ore-materials`.
- `messages.yml`: `actions.alerts-rearmed`, `actions.note-instruction`, `actions.vanish-unavailable`,
  `actions.external-plugin-required`.
- Tests: `PartiallyVisibleVeinScenarioTest` (3 cases, running the real analyser into the real engine) and
  `PlayerSessionTest` (3), plus four vein-classification cases in `VeinAnalyzerTest`.
- **Evidence now accumulates across restarts and over a player's whole recorded time.** Every assessment
  folds the stored record in alongside the live session, so a restart no longer resets the evidence base
  to zero. `HistoryHydrator` (core) rebuilds the past through the existing repository interfaces, so it is
  platform- and dialect-neutral and is tested against fakes that reproduce the queries' ordering;
  `AnalysisHistory` and `HistoryParameters` carry it; `AnalysisService` hydrates on the worker with a
  per-player, per-world cache honouring `analysis.history.refresh-minutes`.
- `PlayerAnalysisWindow` now carries its trajectory (`pathPoints`) and derives `geometry()` from it, so
  the stored mining path and the live movement path can be merged into one honest trajectory instead of
  the geometry being frozen at snapshot time.
- `liveDiscoveryIndex` on the window: the seam between stored history and the live session, so the
  waiting-time model can drop the interval whose length is unknowable rather than guess it.
- `messages.yml` `lifecycle.banner` — a configurable ASCII banner, plus every other console line, printed
  through `ConsoleReporter`. `MessageService.colour`/`stripColour` translate `&`-codes and hex, and fall
  back to colour-stripped logging when there is no console to address.
- Tests: `HistoryHydratorTest` (11 cases: reversal of the newest-first reads, world and player scoping,
  trajectory rebuild, truncation, de-duplication, and the dropped seam interval),
  `HistoryHydrationIntegrationTest` (6 cases against a real embedded SQLite database, through the real
  repositories — the schema, the ordering and the de-duplication are checked against what actually
  ships, not only against fakes), and `MessageServiceTest` (12: colour translation, and integrity checks
  on the shipped `messages.yml`, including that no console placeholder is unsupplied and that the banner
  art stays out of the block-drawing ranges). 120 tests in total.

- **The repository is published and the project is now automated.** Hosted at
  <https://github.com/LB45440078L/xray-anticheat> (private, because the project is proprietary).
  Added: a release workflow that refuses to publish unless the git tag matches the version in *both*
  `pom.xml` and `plugin.yml`, then builds, tests, verifies the jar and attaches the jar plus a SHA-256
  checksum to a GitHub Release; Dependabot for Maven, Actions and the Python tooling; a pull request
  template; and bug-report and security contact issue templates. `docs/DEVELOPMENT.md` section 9 and
  `CONTRIBUTING.md` document the process. CI's first real run — on the commit that published the
  repository — passed.

- **An embedded administration panel.** The plugin can now serve its own moderator console from
  `xray-web`, a new module that depends on nothing but the JDK and `xray-core`: evidence review,
  player inspection, ban-wave candidates and the audit trail, in a browser, with no other plugin or
  service to install. It is **off by default** and bound to loopback; a non-loopback address is
  refused unless `allow-non-loopback` is explicitly set, because the transport is plain HTTP and there
  is no TLS here. It authenticates with one password (PBKDF2-HMAC-SHA256, 210,000 iterations,
  constant-time comparison, per-address lockout), uses `HttpOnly; SameSite=Strict` sessions with CSRF
  tokens on every state-changing request, sends a strict CSP with no inline script, escapes every
  interpolated value, and bounds every read. It cannot edit evidence - it reads through the repository
  interfaces and its only write is an audit entry. `/xray webpassword` sets the password and restarts
  the panel; `xray.web` gates it. Enabled with `web.enabled: true`. It adds no dependency, and the jar
  grew from 403 KB to 412 KB.
- **`ModeratorActionRepository.recent(int)`**, so the audit view can answer "what has been done lately"
  - the per-player and per-moderator queries cannot.
- **56 tests for the panel**, 23 of which drive a real HTTP server over a real socket against a real
  SQLite database: unauthenticated access, wrong credentials, lockout, cookie attributes, CSRF
  enforcement (including that a refused request writes nothing), read-only mode, asset serving, path
  traversal and unsupported methods.
- `tools/ci/verify_jar.sh` now also fails if the panel's assets are missing from the jar, if the source
  references Paper or Adventure API, or if `plugin.yml` carries a Paper-only key.


### Changed

- **The project now targets the Spigot API exclusively, and the `xray-paper` module is now
  `xray-spigot`** (Java package `io.xrayac.spigot`). `paper-api` is no longer a dependency at all;
  the build resolves `org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT` from the SpigotMC repository. The
  refactor was small because the source was already written against `org.bukkit` throughout - the
  entire Paper-specific surface was two calls to `Plugin#getPluginMeta()`, now a documented `version()`
  accessor using `getDescription()`. `folia-supported` was removed from `plugin.yml` as a Paper-only
  key, and the verifier now fails the build if any Paper or Adventure API reappears in the source.
- **SLF4J is now declared in `plugin.yml`'s `libraries:` list, with the `slf4j-jdk14` binding.** This
  inverts a decision from the Paper build and is not cosmetic: `paper-api` depends on `slf4j-api` so a
  Paper server already has it, but `spigot-api` does not and Spigot logs through `java.util.logging`.
  Assuming the server supplied it would have left the plugin with no SLF4J at all and silently
  discarded every log line. The verifier now requires both entries.
- `runtime-libraries` via `libraries:` was verified to be a Spigot feature, not a Paper one:
  `PluginDescriptionFile#getLibraries()` exists in spigot-api and `org.bukkit.plugin.java.LibraryLoader`
  resolves the coordinates with Maven Resolver. The small-jar design therefore survives the migration.

- Menu clicks play a configurable sound (`settings.open-sound`, `settings.click-sound`), resolved per use
  so a corrected name takes effect on reload.
- `docs/CONFIGURATION.md`, `docs/ARCHITECTURE.md` and `docs/ADMIN_GUIDE.md` updated to match: pagination,
  the statistics screen, the actions that report honestly, the vein-level classification rule, and the
  `config-version` check.
- `suspicion.enforcement-mode` now ships as `BAN_WAVE`. It is the only mode in which candidates are ever
  held — under `ALERT_ONLY` a player's record never accrues towards enforcement, so the ban-wave machinery
  sat idle. Automatic execution stays off (`ban-wave.automatic-ban: false`), so a wave is still a proposal
  a moderator approves, and `DecisionPolicy.defaults()` deliberately stays at `ALERT_ONLY` as the
  least-acting safety net when a configuration omits the key.
- `retention.mining-events-days` default raised from `7` to `30`, so the stored trajectory reaches as far
  back as `analysis.history.lookback-days`. The loader warns when the two disagree, because a retention
  shorter than the lookback silently caps the tunnel-geometry signal.
- The startup report is now coloured multi-line console output from `messages.yml` rather than one
  `LOGGER.info` line, and the previously inert `lifecycle.*` messages are wired. `PRIVACY.md`,
  `STATISTICAL_MODEL.md`, `MODERATOR_GUIDE.md`, `PERFORMANCE.md` and the README were corrected where they
  still described `ALERT_ONLY` as the shipped default, and the GDPR Article 22 note now states precisely
  which configurations constitute automated decision-making.
- **The compiler release is now 25** (`maven.compiler.release`, the only place it was declared), down
  from 27. The build still runs on the JDK 27 toolchain, but `javac --release 25` emits `major version
  69` class files and restricts the API to Java 25's, so the jar now runs on any Java 25, 26 or 27 JVM
  instead of demanding the JDK it was built with. No source changed: nothing in the codebase used a
  Java 26/27-only language or library feature, which the compiler would have rejected. Verified by
  reading the class-file major version out of all 135 of the plugin's own classes inside the packed jar.
- **The packaged jar shrank from 14,569,935 bytes to roughly 341 KB — a 97.7% reduction.** It no longer
  bundles HikariCP, sqlite-jdbc, the MariaDB or PostgreSQL drivers, or slf4j-api. The four libraries
  are declared under `libraries:` in `plugin.yml`, which makes Paper fetch them from Maven Central on
  first start and add them to the plugin's classpath; slf4j-api was already on the server's classpath,
  since `spigot-api`'s own POM depends on it. Two things made the old jar large: sqlite-jdbc's native
  binaries for five platforms (11.5 MB, 82% of the total, for a plugin that runs on one), and shade
  bundling the external drivers at all. Our own two sibling modules are still merged in, because they
  are not published to Maven Central and cannot be fetched at runtime — and because that is easy to get
  wrong, `tools/ci/verify_jar.sh` now asserts that all three of our module trees are present, that none
  of the five external libraries is, that `plugin.yml`'s library versions match the POM's, and that the
  jar stays inside a 4 MB budget.
- **Relocation is gone**, along with the notice-merge transformers. With no third-party code in the jar
  there is nothing to relocate or merge, and dropping HikariCP's relocation to `io.xrayac.libs.hikari`
  removes a maintenance burden that existed only because it was bundled. The shade plugin remains, for
  the single purpose of merging our own sibling modules.
- **`THIRD-PARTY-NOTICES.txt` and the third-party section of `LICENSE.txt` rewritten**: the components
  are depended upon but not redistributed now, so nothing has to be carried in the jar. This also
  materially improves the licensing position — the LGPL-2.1 MariaDB Connector/J is no longer
  redistributed by this project, so the obligations that previously attached to distributing the plugin
  now sit with the library's publisher and the server operator. The GPL-linked Paper API question is
  unchanged and still flagged.
- `docs/DEVELOPMENT.md`, `docs/ADMIN_GUIDE.md`, `docs/DATABASE.md` and `README.md` updated for the new
  packaging, including a measured size breakdown and a new "Servers without internet access" section
  covering the Maven mirror, cache pre-seeding and manual options.
- The README's stated test count corrected from a stale 84 to 120.

## [1.0.0] - 2026-10-01

The initial release. A complete, self-contained statistical X-ray anti-cheat: it collects mining
observations, accumulates an explainable body of evidence, presents it to moderators, and can
defer enforcement into ban waves. It is designed and documented to be run in `ALERT_ONLY` mode
while a server builds confidence in it.

