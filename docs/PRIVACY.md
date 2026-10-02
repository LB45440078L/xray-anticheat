# Privacy

This document states, concretely and completely, what XRay AntiCheat records about players, how
long it is kept, what it deliberately does **not** record, who can read it, and how an operator
can reduce or eliminate that storage. It is written for a server operator who is answerable for
the data, not as marketing.

The short version: the plugin keeps **behavioural records about identifiable players** — where
they mined, what ore they found, and what the system inferred about them — plus a moderator
audit trail. There is no chat, no inventory, no private message and no account-credential data
anywhere in it. The excavation ledger and the mining events are the sensitive part, and they
should be treated as personal data under regimes such as the GDPR.

---

## 1. What is stored about players

Everything is stored in the operator's own database (`plugins/XRayAntiCheat/xray.db` by default,
or a self-hosted MariaDB/PostgreSQL). Nothing is transmitted off the server; the plugin has no
telemetry and no external service. All keys are the player's **UUID**; the name is a display
attribute.

The complete list, table by table. A table marked *(unused)* exists in the schema but no
repository reads or writes it yet — it would carry the same category of data if and when its
layer is built.

| Table | What it contains about a player | Status |
| --- | --- | --- |
| `players` | UUID, current name, first-seen time, last-seen time | used |
| `player_sessions` | session start/end times, blocks mined and distance travelled per session | *(unused)* |
| `world_modifications` | **the excavation ledger**: every block removal — world, exact `(x, y, z)` coordinate, who removed it (player UUID), and when | used |
| `mining_events` | **every attributed block break** — player UUID, world, exact `(x, y, z)`, the block type, the origin attribution, the server tick and the wall-clock time. (`session_id` is currently written as `NULL`.) | used |
| `ore_discoveries` | one row per ore **vein** encounter — player UUID, world, ore type, the broken block's `(x, y, z)`, its exposure classification, vein size and hidden/exposed block counts, blocks mined and distance travelled since the previous discovery, the movement- and look-alignment angles toward the ore, an evidence discount, and the timestamp | used |
| `ore_veins` | reconstructed vein geometry (centroid, axis, linearity/planarity/extent) attached to a discovery | *(unused)* |
| `suspicion_snapshots` | **suspicion assessments** — player UUID, world, evaluation time, prior/effective/posterior log-odds, suspicion score, statistical confidence, decay factor, sample size, independent-group count, and the evidence band | used |
| `evidence_events` | one row per contributing signal per assessment — the component id, its independent group, its log-likelihood ratio, reliability, sample size, and **a human-readable explanation sentence** | used |
| `ban_wave_candidates` | players held for deferred enforcement — player UUID, world, first/last detection times, peak suspicion/confidence/band, sample and group counts, and an **evidence summary** (a multi-line report) | used |
| `ban_waves` | a record of each planned/executed wave — times, candidate count, whether it was automatic, and a summary that **contains player display names** | used |
| `moderator_actions` | **the moderator audit trail** — player UUID, acting moderator UUID (or `NULL` for an automated action), the action label (`AUTOMATED_KICK`, `KICK`, `BAN`, `BAN_WAVE`, `FLAG`, `NOTE`), a free-text note or evidence summary, and the time | used |
| `world_analysis` | which worlds were analysed and with which ore ids | *(unused)* |
| `plugin_metadata` | plugin-level key/value state (e.g. ban-wave scheduling) | *(unused)* |
| `schema_migrations` | the migration ledger; no player data | used |

Two details worth being explicit about:

- **The explanation and note text can contain coordinates and reasoning.** The
  `evidence_events.explanation`, the `ban_wave_candidates.evidence_summary` and the
  `moderator_actions.note` are the sentences a human reads. The `ore-targeting` explanation, for
  instance, quotes the ore's `(x, y, z)`, and an automated kick writes the whole snapshot
  explanation into the audit note. So the audit text is itself location data, not merely a score.
- **The excavation ledger covers every player, not just the one being investigated.** It records
  removal provenance so the analyser can distinguish "found a hidden ore" from "walked into
  somebody else's tunnel". It is the largest and most broadly-scoped dataset the plugin keeps.
- **Automated enforcement is recorded against the player with a `NULL` moderator.** A server running
  `IMMEDIATE`, or `BAN_WAVE` with `automatic-ban: true`, therefore writes audit rows describing an
  automated decision. The shipped defaults — `BAN_WAVE` with `automatic-ban: false` — do not: a wave is
  planned for a moderator to approve, and every resulting action carries that moderator's identity.
  `ALERT_ONLY` also does not, and additionally holds no candidates at all.

---

## 2. What is NOT stored

Stated plainly, because "we collect mining data" often implies more than it should:

- **No chat messages.** The plugin does not subscribe to chat and does not record anything typed
  in chat.
- **No private messages or `/msg` conversations.**
- **No commands a player types.**
- **No inventory contents** — not items held, not pickups, not chests or any container. The
  plugin is interested in blocks in the world, not possessions.
- **No continuous location trace.** Player movement feeds an in-memory path buffer to compute
  trajectory geometry, but that buffer is not written to the database as a position history.
  What is persisted is the location of *broken blocks* (`mining_events`) and *ore discoveries*
  (`ore_discoveries`), which are behavioural location records but not a full movement log.
- **No IP addresses, no email, no authentication or account-credential data.** The plugin never
  sees them; they live in the server's own login system and are outside this plugin entirely.
- **No connection metadata beyond the name and UUID**, and no data about anyone who has never
  mined or been observed.
- **No skin, cape or other account cosmetics.**
- **No telemetry.** Nothing leaves the server. There is no "phone home".

The `metrics` maps on evidence contributions are also not persisted (see
[`DATABASE.md`](DATABASE.md) §7): the durable record is the explanation sentence, not every raw
quantity behind it.

---

## 3. How long it is kept

Nothing is kept forever. Retention is configured in `config.yml` under `retention:` and enforced
by the `pruneRetention` task, which runs hourly and acts only during `retention.prune-hour`.

| Data | Config key | Default | Enforced by |
| --- | --- | --- | --- |
| Per-block mining events (largest table) | `retention.mining-events-days` | 7 days | `pruneRetention` |
| Ore discoveries | `retention.ore-discoveries-days` | 90 days | `pruneRetention` |
| Suspicion snapshots + their evidence rows | `retention.suspicion-snapshots-days` | 180 days | `pruneRetention` |
| Excavation ledger (persisted) | `retention.world-modifications-days` | 30 days | `pruneRetention` |
| Ban-wave history | — | **retained indefinitely, by design** | the enforcement audit trail |
| Master switch | `retention.enabled` | true | — |
| Time of the daily prune | `retention.prune-hour` | 04:00 server local | — |

The in-memory excavation ledger has its own, separate and shorter bound
(`performance.ledger-retention-hours`, default 72 hours), pruned every 30 seconds.

**Two honest caveats.**

1. **Ban-wave history is retained indefinitely, deliberately.** A wave runs at most once a day, so
   the table grows by a few hundred rows a year. It is the record of who was removed and why, and an
   operator who wants it gone must delete those rows themselves. If your data-protection policy
   requires a finite retention period for enforcement records, add a scheduled `DELETE` against
   `ban_waves` outside the plugin.
2. `ban_wave_candidates` is dropped by the ban-wave planner's own TTL
   (`ban-wave.candidate-ttl-hours`, default 14 days) during planning, not by the retention task;
   a candidate that is never planned over can outlive that TTL in the table.

Everything else in the table above is applied automatically while `retention.enabled` is true and
the hour matches.

---

## 4. Who can read it

### In-server access

Access is governed by permission nodes, declared in `plugin.yml` (all default `op`):

| Permission | Grantee sees / can do |
| --- | --- |
| `xray.alerts` | receive live alert messages (player, world, verdict, score, top signal) |
| `xray.inspect` | open the evidence report, mining statistics, assessment history, GUI; add notes; flag; run `/xray inspect`, `/xray stats`, `/xray history`, `/xray gui` |
| `xray.admin` | view `/xray status` (plugin, storage, worker and ledger health) |
| `xray.banwave` | review, plan and approve ban waves |
| `xray.teleport` | teleport to a player from the interface *(the GUI action currently only acknowledges — see [`CONFIGURATION.md`](CONFIGURATION.md) §4)* |
| `xray.freeze` | freeze a player *(GUI action currently only acknowledges)* |
| `xray.kick` | kick a player from the GUI |
| `xray.ban` | ban a player from the GUI |
| `xray.reload` | reload the plugin configuration |
| `xray.debug` | toggle debug logging |

`xray.*` grants all of the above. Each subcommand and each GUI item checks its own permission;
the GUI additionally re-checks on click rather than trusting the render pass. Opening the
interface does not create, adjust or acknowledge suspicion (`actions.inspect-note` says so to the
moderator), so looking at a player cannot change their record.

### Out-of-band access

Anyone with filesystem access to the SQLite file, or database credentials for MariaDB/PostgreSQL,
can read everything. The plugin does not encrypt its data at rest: it relies on the database's
own access controls and the file permissions of the server host. Credentials for a remote database
can be supplied through an environment variable (`XRAY_DB_PASSWORD` by default) rather than
written into `database.yml`, precisely because configuration files end up in backups and version
control.

The data is never sent anywhere else, so the list of readers is: server operators, moderators
holding the permissions above, and anyone with database/host access.

---

## 5. Reducing or disabling storage

There is **no supported "analyse but never persist" switch**. The options, from least to most
disruptive:

1. **Shorten retention.** Set the `retention.*-days` values as low as your operation tolerates
   (the minimum accepted is 1 day). This bounds how much behavioural history exists at any time.
2. **Turn the retention task's switch off and prune externally**, if you prefer to control
   deletion from your own database tooling: set `retention.enabled: false`. (Note the ban-waves
   caveat in §3 either way.)
3. **Delete specific players' data.** Because identity is the UUID, an erasure request can be met
   by deleting that UUID's rows from every table (`players`, `world_modifications` by `actor_id`,
   `mining_events`, `ore_discoveries`, `suspicion_snapshots` and its `evidence_events`,
   `ban_wave_candidates`, and `moderator_actions` by `player_id`). There is no cascade, so each
   table must be cleared explicitly. Take a backup first if the deletion must be provable.
4. **Set `suspicion.enabled: false`.** Assessments are still recorded and stored, but no alert is
   raised and no action is taken. This is "do not act on this", not "do not collect".
5. **Set `analysis.enabled: false`.** The config comment says the plugin still records data but
   performs no assessment; it is a diagnostic switch, not a privacy one. The only way to stop the
   plugin writing is to stop it running.
6. **Run without a reachable database.** With `storage.fail-safe.continue-without-database: true`,
   an unreachable database means the plugin runs in memory only and persists nothing — but that is
   a failure mode, not a supported configuration, and ban-wave enforcement is suspended while it
   lasts. Do not rely on it as a data-protection measure.

If your server's policy is "collect nothing", the honest position is that this plugin is the
wrong tool: its entire purpose is to keep behavioural records. Choose a different mitigation.

---

## 6. Data protection: GDPR and similar regimes

This section is written plainly because the data is plainly personal.

- **The operator is the data controller.** The plugin is a tool run by the server; it does not
  itself act as a controller or processor. Whoever runs the server decides why the data exists and
  is answerable for it.
- **The data identifies individuals.** `mining_events`, `world_modifications` and
  `ore_discoveries` are records of what a named, identifiable person did, when, and where. They
  are behavioural personal data, and the excavation ledger in particular is a broadly-scoped
  record of everyone's activity. Treat them accordingly.
- **Legal basis.** Anti-cheat is commonly run on a legitimate-interests basis (or, on a
  Minecraft network with a contract, on contract performance). That determination is the
  operator's to make and document, not the plugin's.
- **Data minimisation.** The schema stores what the model needs and no more: there is no chat, no
  inventory, no movement trace, no IP, no credentials. The non-persistence of component `metrics`
  is a further minimisation. Keep retention short and this is defensible; keep everything for
  years and it will not be.
- **Transparency.** Players should be able to find out that the server runs statistical mining
  analysis. A server rule or privacy notice is the normal way to say so.
- **Data-subject rights.** Access and erasure can be honoured by reading or deleting the rows for
  a UUID (§5.3). Portability is satisfiable by exporting those rows; there is no portable export
  tool shipped, so this is manual or scripted.
- **Automated decision-making (GDPR Article 22).** With the shipped defaults — `BAN_WAVE` mode and
  `automatic-ban: false` — the system makes **no** decision with a legal or similarly significant
  effect: it produces assessments and a candidate list for a human to review, and nothing happens to a
  player until a moderator approves it. `ALERT_ONLY` also makes no such decision. But `IMMEDIATE` mode,
  and `BAN_WAVE` with `automatic-ban: true`, **do** turn a statistical score into automatic enforcement
  (and write the corresponding `AUTOMATED_KICK` / `BAN_WAVE` audit rows). If you use those, you are
  operating automated decision-making and should be able to offer human review, and to explain the logic
  — which this system is unusually well suited to, because every assessment stores the explanation
  sentence that produced it and the ban reason string carries the verdict band, observation count and
  independent-signal count.
- **Special care with minors.** A large share of Minecraft players are children, and their
  behavioural data deserves a higher standard of care, not a lower one.
- **Where the data lives.** A self-hosted SQLite file or database keeps the data in the operator's
  own jurisdiction; migrating a network to a shared remote database moves it, which is itself a
  decision.

**The direct statement.** The excavation ledger and the per-block mining events are behavioural
records about identifiable players. Under the GDPR, and under any comparable regime, they are
personal data, and an operator who collects them has the obligations that follow. Short retention,
minimised collection, a documented lawful basis, transparency to players, and human review of
enforcement are the practical defences — and this document exists so that an operator can see
exactly what they are collecting before deciding to.
