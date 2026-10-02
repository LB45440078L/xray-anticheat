# XRay AntiCheat

### Statistical ore-vision detection with evidence a moderator can actually read.

**X-ray isn't a cheat you can see. Now it's a case you can prove.**

---

## The problem with every other anti-xray

Ore-vision cheats leave no trace. No impossible movement, no fly, no reach. Just a player who
competently walks to diamonds they should not have known were there. So anti-xray plugins end up doing
one of two things: counting ore per hour, or flagging anyone whose tunnel is suspiciously straight.

Both fail the same way. They cannot tell a lucky player from an informed one, so your staff get a number
with no argument behind it. And your players find out that digging straight, running a strip mine, or
exploring a deepslate cave is enough to look guilty.

**XRay AntiCheat asks a different question:** *how improbable is this player's sequence of behaviour
under legitimate mining, compared with mining guided by ore vision?* That question has an answer you can
put in front of a moderator — and, if it comes to it, in front of the player.

---

## Why it is different

**1. It reasons like a statistician, not a tripwire.**
Five independent evidence families are combined as log-likelihood ratios: buried-discovery rate,
inter-discovery waiting times, exposure mix, pre-exposure ore targeting, and tunnel geometry. A verdict
requires both a minimum number of observations and a minimum number of *independent* signal families —
so no single unusual habit, however strong, can convict anyone.

**2. Every verdict explains itself.**
Each assessment writes its own explanation. A moderator opens a player and reads the argument, with the
actual numbers, instead of being handed a score and told to trust it.

**3. It is built to protect the innocent.**
This is the part that decides whether your staff will ever actually use it:

- **Sample-size restraint.** A handful of finds is never enough. Below the configured minimum the verdict is simply *insufficient evidence*, no matter how extreme the numbers look.
- **Uncertainty never counts against a player.** If the world state cannot be determined, the observation is set aside rather than guessed. Missing data earns neither suspicion nor credit.
- **One vein, one discovery.** A player who mines out a seam they could clearly see is not punished for tidiness.
- **A vein is judged by its most visible block.** Approach a vein from the side and the first block you break is often enclosed — that is not hidden ore, and it is not treated as hidden ore.
- **Legitimate behaviour is modelled, not punished.** Cave exploration, strip mining, branch mining, straight tunnels and plain good luck are all represented in the model.

**4. Evidence accumulates instead of resetting.**
Most detection dies at the restart: the evidence restarts from zero, so a patient cheater is judged on a
few hours. Here, every assessment reads the player's stored record back in — up to 90 days by default —
so the numbers describe a player's whole time on your server. That is what makes a ban wave defensible
rather than a snap judgement.

**5. No machine learning. No training. No calibration period.**
It ships with priors derived from how the game actually generates ore, and it works on install. Nothing
to label, nothing to train, no telemetry to collect before it becomes useful. Tune it later if your world
differs — you are tuning a model you can read, not a black box.

---

## Features

- **Per-ore statistical models** — diamond, emerald and ancient debris ship separately modelled, because their generation genuinely differs. Add any other ore through configuration.
- **Exposure classification** — fully exposed, partially exposed, conditionally exposed, hidden, or (honestly) unknown.
- **Vein reconstruction** — connected-component analysis of the vein, with its size, shape and which parts were reachable.
- **3D trajectory analysis** — principal-axis direction, straightness, curvature, turning density and vertical drift, in three dimensions, never flattened to a map.
- **Ore targeting** — whether a player's heading already pointed at an ore *before* it could be seen.
- **Ban waves** — candidates accumulate with a TTL, waves are planned on an interval, and by default a wave *proposes* a list for a human to approve. Automatic execution is off out of the box.
- **Moderator interface** — paginated player list sorted by suspicion, per-player evidence page, per-ore breakdown with its own icons, and all moderation actions behind their own permissions.
- **Three databases** — SQLite (zero setup), MariaDB, PostgreSQL. All through a HikariCP pool, with batching and forward-only migrations that never destroy existing data.
- **Never blocks the server** — collection is event-driven and lightweight on the main thread; all analysis and every database operation runs on worker threads by design.
- **Bounded memory** — per-player buffers have configurable caps and are discarded oldest-first, so hundreds of players do not grow without limit.
- **Data retention you control** — separate retention per data class, pruned on a schedule, plus a documented privacy posture.
- **A jar that is actually small** — 330 KB, not the usual 14 MB. It no longer ships five platforms' worth of native SQLite binaries you will never run, and it gets its database drivers from Paper's own library loader on first start. No other plugin required, no bStats, no telemetry, and no outbound connections other than to the database you configure.
- **Python 3D visualiser** — included. Load a player's history from SQLite, MariaDB or PostgreSQL and see the trajectory, tunnels, veins, exposed vs buried ores and the targeting geometry in an interactive 3D view.
- **120 automated tests** — the analytical engine, the SQLite integration path and the shipped configuration are all covered by a test suite that runs in a plain JVM.

---

## Commands

| Command | What it does |
| --- | --- |
| `/xray help` | Command overview |
| `/xray status` | Plugin health, storage, queue, tracked players, candidate count |
| `/xray inspect <player>` | Full evidence report for a player |
| `/xray stats <player>` | Mining statistics per ore |
| `/xray evidence <player>` | The written explanation behind the verdict |
| `/xray history <player>` | Recent assessments with band, score, confidence and signal count |
| `/xray gui [player]` | Open the moderator interface |
| `/xray banwave [status\|plan\|approve]` | Review candidates, plan a wave, approve and execute it |
| `/xray note <player> <text>` | Attach a note to a player's record |
| `/xray reload` | Reload configuration without restarting |
| `/xray debug` | Toggle debug logging |

Aliases: `/xrayac`, `/ac`.

---

## Permissions

The permission tree is graded on purpose. Reading a status page, inspecting a player, teleporting to
them, kicking them and banning them are five different levels of trust — a single umbrella node would
either over-restrict ordinary moderators or hand every inspector the power to remove players.

| Permission | Grants |
| --- | --- |
| `xray.inspect` | Inspect players, read evidence, open the interface (also the command's base permission) |
| `xray.alerts` | Receive alerts when evidence reaches a threshold |
| `xray.admin` | View plugin status and health |
| `xray.teleport` | Teleport to a player from the interface |
| `xray.freeze` | Hold a player in place while investigating |
| `xray.kick` | Kick from the interface |
| `xray.ban` | Ban from the interface |
| `xray.banwave` | Review, plan and approve ban waves |
| `xray.reload` | Reload configuration |
| `xray.debug` | Toggle debug logging |
| `xray.*` | Grants all of the above |

---

## Requirements

- **Server:** Paper (declared API version 26.2). Not Folia-compatible.
- **Java:** 25 or newer.
- **Database:** none required — SQLite needs no external database server. MariaDB or PostgreSQL for a network of servers sharing one record.
- **Network on first start:** the plugin fetches its four database libraries from Maven Central the first time the server starts, then caches them. That is the trade that keeps the download at 330 KB instead of 14 MB. Offline servers can pre-seed the cache or point at an internal Maven mirror, and the setup guide covers both.

---

## Straight answers to the usual questions

**Will it ban my players on its own?**
No — not unless you tell it to. It ships in ban-wave mode with automatic execution **off**: candidates
accumulate and a wave is prepared for a moderator to approve. An alert-only mode exists if you want it
to take no action at all while you evaluate it.

**Can it tell me the odds it is wrong?**
No, and be suspicious of anything that claims to. What it gives you is a calibrated likelihood that the
observed behaviour matches ore-vision rather than legitimate mining, the sample size behind it, and the
number of independent signals — which is what a human needs to judge. Treat the output as evidence, not
a verdict. Your staff make the call.

**Will it flag my strip miners and cave explorers?**
Those behaviours are modelled as legitimate and are represented on both sides of the comparison. The
gates above exist specifically to stop unusual-but-honest play from reaching a verdict. If you do find a
false positive, the evidence report tells you exactly which signal caused it — that is the point of the
design.

**Does it need training or a baseline period?**
No. It is not a machine-learning system, and it needs no labelled data, no server-specific training and
no calibration to start working.

**What about performance?**
Every database call and all statistical work runs off the main thread by design, collection is
event-driven, buffers are bounded, and persistence is batched. **No TPS benchmark is published, because
none has been measured on your server** — measure it on yours during the evaluation period before you
roll it out.

**Does it stop other kinds of cheating?**
No. It does one thing: ore-vision detection, done properly. It will not replace your movement or combat
anti-cheat.

---

## What you get

- The built plugin jar, ready to drop into `plugins/`
- Fully commented configuration — every option documented, nothing hard-coded
- The Python 3D visualiser, with its dependency list
- Complete documentation: administrator, moderator, architecture, mathematical model, database and privacy
- First-run setup is one file drop plus a restart; the plugin writes its own defaults on first start

---

## Buy

### {{PRICE}}

One-time purchase. No subscriptions, no per-server fees, no telemetry.

**{{PURCHASE_LINK}}**

Questions before you buy? Ask below and I will answer them here.

---

## What it is not

Being clear about the limits, because false confidence is what gets players wrongly banned:

- It is a statistical inference system, not proof. It reports evidence; a human decides.
- It is not warranted to be free of false positives or false negatives. No detector is.
- It detects ore-vision cheating only.
- It cannot distinguish a cheater from a player who was told where to dig by someone else. It measures behaviour, not intent.
- It ships with sensible defaults, not constants measured on your world. The ore priors are the thing most worth tuning to your terrain, and the configuration explains how.
- Folia is not supported.
