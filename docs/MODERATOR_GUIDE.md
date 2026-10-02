# Moderator Guide

This is a practical guide to investigating an XRay AntiCheat alert. It is a workflow, not a
reference; the command reference is in `README.md` and the configuration reference is in
`docs/ADMIN_GUIDE.md`.

Read this first, and read it again before you ban anyone:

> **You must not punish a player on a single statistic.**
>
> **Unusual mining is not cheating.** Cave exploration and strip mining are legitimate. They are
> common, they are how most players actually play, and the system is specifically designed to account
> for them.
>
> **"Insufficient evidence" means exactly that.** It is not a suspicion, it is not a soft
> accusation, and it is not permission to watch the player more closely. It means the system does
> not have enough to say anything, and it says so.

Your job is not to confirm the plugin. Your job is to look at the evidence, look at the player, and
reach an honest conclusion. Sometimes that conclusion is "this is an honest player" — and that is a
completely normal outcome, not a failure.

---

## 1. What an alert is, and is not

An alert means: *this player's behaviour, as the model understands it, is more consistent with
ore-informed mining than with legitimate mining, by enough to bring to a human.* That is all.

It is **not** a verdict, and the plugin says so on every report. The numbers are likelihood ratios
and probabilities, not proof. A high band on a player who turns out to be an exceptionally lucky or
efficient miner is the system working as designed — it noticed something worth a human's attention.
An honest explanation from you is the correct end of the process, not a bug report.

Four things will help you read every report correctly:

| Term | What it means |
| --- | --- |
| **Verdict / evidence strength** | The banded conclusion: insufficient, weak, moderate, strong, very strong. Weak and above require enough data; insufficient is everything below that. |
| **Suspicion score** | How much the behaviour favours ore-vision, 0–1. It can look dramatic on thin evidence, which is why it is never used alone. |
| **Statistical confidence** | How much the score can be trusted given how much data backs it, 0–1. A high score with low confidence is "startling, but we have barely looked". |
| **Sample size / signals** | How many independent observations, across how many independent kinds of signal. A verdict needs enough of both. |

If you remember one thing: **a strong band with a high confidence, resting on a decent sample from
two or more independent signals, is worth investigating. Anything less is not.** And even the former
is worth investigating, not acting on.

---

## 2. The escalation flow

Follow the steps in order. Stop at any point where the evidence clearly favours the player; there is
no obligation to reach the end.

### Step 1 — The alert

You receive an alert in chat (or see a head at the top of `/xray gui`). Read the headline: the
player, the world, the verdict, the score, the confidence, the decibans, the observation count and
the number of independent signals. Note the top signal, but do not decide anything yet.

### Step 2 — Open the player

```
/xray inspect <player>
```

This prints the full evidence report: the verdict, the four numbers, and the contributions listed
strongest-first, each labelled with whether it argues for (`+`) or against (`−`) the player. Append
`gui` to open the interface at the same time, or use `/xray gui <player>`.

Opening an inspection **generates no evidence** against the player. Looking is not an accusation.

### Step 3 — Read the verdict and the confidence

Look at both together.

- **Moderate or weak** — weak evidence is normally below the alert band; if you are here, look hard
  at whether it is really actionable at all.
- **Strong or very strong** — worth serious attention, *if* the confidence supports it.
- **Low confidence** — the assessment rests on thin data. A startling score with low confidence is a
  reason to keep watching, not to act.

Ask: does the confidence fit the sample size you are about to check? A high band on a handful of
observations should not be possible — the engine gates on that — but a cautious score backed by
modest data is common and should not be over-read.

### Step 4 — Check the sample size and the number of signals

```
/xray stats <player>
```

and look at the observation count and signal count on the report.

- **How many observations?** A verdict needs at least the configured minimum (ten by default). Fewer
  than that and the system would have said *insufficient evidence*.
- **How many independent signal families?** At least two by default. This matters: any single signal
  can be evaded, so the system refuses to act on one family however strong it is. If a report shows
  only one family contributing, the engine should not have produced an actionable band.
- **What is the mixture?** A verdict is most trustworthy when rate, mix and targeting agree, and
  least trustworthy when it rests on the minimum. Read which contributions are actually large.

### Step 5 — Check the buried / exposed split

Still in `/xray stats <player>`, and on the player-detail GUI page:

- **Buried (hidden)** discoveries are ore the player could not have seen before mining to it — they
  had to work for it.
- **Exposed** discoveries are ore that was already visible — in a cave, in a ravine, or in a tunnel
  someone else dug.

**A high exposed share argues in the player's favour.** A player who spends a session in a big cave
system finds plenty of ore, but mostly exposed ore, and the exposure-mix component records that as
genuine evidence *against* ore vision. Unusual discovery counts explained by legitimate cave
exploration should genuinely reduce suspicion, and in this system they do. If you see a large exposed
share, you are probably looking at an explorer or a ravine-walker, not a cheater.

### Step 6 — Check the trajectory / orientation evidence

In the report, look for the targeting contributions. These describe what the player's **movement** and
their **camera** were doing *before* they reached a buried ore — at a fixed distance back, far enough
that the ore was still comfortably out of sight.

- **Movement alignment** is weak evidence by design: a strip-miner walks in a straight line and mines
  what is ahead, so the ore they reach is almost by construction in front of them. Do not treat
  movement alignment as suspicious on its own.
- **Look (camera) alignment** is the sharper signal: did the player's view point at the ore *through
  solid rock*, repeatedly? The baseline is 0.067 — the exact probability that an aimless direction
  falls inside a 30° cone — so repeatedly aiming into solid rock exactly where a vein sits is
  genuinely unusual. This, sustained across many veins and corroborated elsewhere, is the kind of
  thing an ore-vision user produces and an honest player does not.

### Step 7 — Check the tunnel geometry evidence

Geometry contributions are small by design — the component is capped at a likelihood ratio of 2 in
either direction and declares low reliability, precisely because there is no tunnel shape a cheater
must adopt and none an honest player must avoid. Strip mining produces long straight efficient
tunnels; so does ore-seeking. Branch mining produces a regular grid; so does a systematic cheat.

**Treat geometry as a nudge, never a reason.** If geometry is the main thing that pushed an
assessment up, you do not have a case.

### Step 8 — Inspect the player in world

This is the step that no statistic can replace. From the GUI: teleport (with `xray.teleport`),
teleport while vanished, or spectate. Watch them mine. Note whether they are exploring naturally,
strip mining, branch mining, following someone else's tunnels, or behaving unusually. None of those
on its own is cheating.

Remember: teleporting, spectating and inspecting **generate no evidence** for or against the player.
Your judgement is the evidence at this stage.

### Step 9 — Decide

Only now. Reasonable outcomes:

- **No action.** The most common correct outcome. If exploration, strip mining or luck explain it,
  that is the answer. Record a note anyway — it helps the next moderator.
- **Keep watching.** Evidence is building but you are not convinced. Do nothing to the player; the
  system keeps accumulating, and you can look again later with `/xray history`.
- **Record a note or a flag.** Mark your assessment on the player's record without acting on them.
- **Escalate to a senior moderator or administrator.** Bring it to someone else before anything
  irreversible.
- **Kick or ban** — only if, having done everything above, you are personally satisfied and you hold
  the permission. Kick is reversible; ban is not. Never ban on the strength of one number.

If you are reaching for a punishment and the strongest thing you can say is "the score was high",
stop. That is not enough.

---

## 3. Using the tools

| Command | When to use it |
| --- | --- |
| `/xray inspect <player>` | Start here. Full report with the four numbers and every contribution, labelled for/against. |
| `/xray inspect <player> gui` | Same, and open the interface. |
| `/xray stats <player>` | The buried/exposed split per ore, blocks mined, distance travelled, session duration. |
| `/xray evidence <player>` | The current breakdown on its own, if you only need that. |
| `/xray history <player>` | Recent stored assessments, so you can see whether this is a one-off or a pattern. |
| `/xray gui [player]` | The full interface: player list sorted by suspicion, detail page, statistics, actions. |
| `/xray note <player> <text>` | Attach your finding to the record. |

**Always record a note.** "Checked 2026-10-01, player was cave exploring in a large deepslate system,
high exposed share, no action." Six months later that note is what stops the next moderator starting
from scratch. The plugin stores notes on the player's record; they are part of the history you are
building.

The GUI at a glance: the **player list** shows tracked players sorted by suspicion, each head with
verdict, score, confidence, decibans, signals and the buried/exposed split. **Player detail** holds
the evidence report and the actions — teleport, teleport-while-vanished, spectate, freeze, add note,
acknowledge alert, flag, kick, ban. Kick and ban ask you to confirm; the confirmation is logged
against your name. If an item is greyed out, you lack its permission — there is no hidden way to use
it.

---

## 4. Three worked scenarios

These are composites, but the numbers are of the kind and magnitude the system actually produces.
They illustrate the three patterns you will meet most often.

### Scenario A — The legitimate cave explorer (no action)

A player spends two hours in a large deepslate cave system. The alert fires because the raw discovery
count looks high.

- **Verdict**: moderate. **Confidence**: 0.62.
- **Observations**: 28 across 2 signals.
- **Buried / exposed**: 9 buried, 46 exposed — a hidden fraction of about 0.16.
- **Trajectory / orientation**: movement alignment unremarkable; look alignment at baseline — the
  camera is not pointing into rock ahead of them.
- **Tunnel geometry**: small negative-to-neutral; they wandered, as cave explorers do.

**Reading it**: the discovery count is high, but almost all of it is exposed. They walked into a cave
and took what was lying there. The exposure-mix component records this as evidence *against* ore
vision, and the targeting signals are at baseline. There is no story here in which the player knew
where buried ore was, because they mostly did not find buried ore.

**Decision**: no action. Note it. This is the system working correctly — it raised a flag on an
unusual-looking session, and the evidence itself explains it.

### Scenario B — The legitimate strip miner (no action)

A player strip mines at Y = -59 for three hours with an Efficiency pickaxe. Straight, fast, and
efficient.

- **Verdict**: weak to moderate. **Confidence**: 0.70.
- **Observations**: 34 across 2 signals.
- **Buried / exposed**: 31 buried, 5 exposed — most finds are buried, as you would expect: they dug
  to reach them.
- **Trajectory / orientation**: movement alignment high — but the model expects this of every
  strip-miner (0.5 baseline), so it contributes little. Look alignment at or near baseline.
- **Tunnel geometry**: long, straight, efficient — and therefore *not* decisive, because strip mining
  and ore-seeking look alike.

**Reading it**: a strip miner genuinely makes buried finds at a decent rate, because they are moving
rock and reaching veins. This is the single most common false positive in naive detectors, and it is
why the model weights movement alignment so weakly and caps geometry so hard. The tell that this is
legitimate is the *absence* of the sharper signals: their camera is not aiming into solid rock ahead
of them, and the geometry nudge is neutral when everything else argues for the player.

**Decision**: no action. Note it, and if you see it repeat across many strip miners, tell your
administrator — the priors may need tuning for your world. That is a configuration conversation, not
a punishment.

### Scenario C — The ore-vision user (escalate; act only on independent corroboration)

A player finds far more buried ore than effort should allow, repeatedly, and the pattern is confirmed
by different kinds of signal.

- **Verdict**: very strong. **Confidence**: 0.93.
- **Observations**: 61 across 3 independent signals.
- **Buried / exposed**: 44 buried, 3 exposed — a hidden fraction near 0.94, against a legitimate
  baseline of 0.55. They are almost never finding ore the honest way.
- **Hidden-discovery rate**: roughly four to nine times the expected buried yield per block of rock
  moved, sustained over a long window.
- **Inter-discovery waiting**: gaps clustered tightly just above the shortest possible tunnel to a
  neighbouring vein — too short, too uniform.
- **Trajectory / orientation**: look alignment far above the 0.067 baseline — repeatedly aiming into
  solid rock exactly where a vein turns out to be, across many veins. Movement alignment also high,
  but it is the *look* signal, corroborated, that matters.
- **Tunnel geometry**: efficient approaches with very little wasted motion between buried veins.

**Reading it**: this is what the model is for. Multiple independent signal families — rate, mix,
targeting — agree, the sample is large, confidence is high, and the pattern has no legitimate
explanation: a known-honest miner does not aim into solid rock at veins they cannot see, four to nine
times more often than chance, while finding almost no exposed ore. Note that this conclusion does not
rest on any single statistic: remove any one signal and the others still describe the same behaviour.

**Decision**: this is the case for escalation. Go and observe the player in world, confirm with
someone senior, and record the evidence. If your server's policy and your permissions support a kick
or ban, act — but on the *body* of evidence, having done every step above, not on the headline
number. If your server is in `ALERT_ONLY`, or in `BAN_WAVE` with automatic execution off (the shipped
default), the plugin will not act for you; that is deliberate, and the decision is yours and your
staff's.

---

## 5. Talking to the player, and appeals

### If you approach the player

Be plain, factual and non-accusatory. There is a player behind the account, and an alert is not guilt.

- Do not accuse. Say what you are doing and why. "I'm looking at mining patterns; can I ask how you
  normally mine?" is a conversation. "Stop X-raying" is not, and it is not justified by a statistic.
- If you ask them to explain, listen to the answer. A cave explorer will tell you about the cave. A
  strip miner will tell you their Y level. The statistics should already be consistent with a
  truthful answer; if they are not, that is information, not proof.
- Never tell a player the exact thresholds or what specifically tripped the system. It teaches
  evasion and it is not a fair summary of what the evidence is.
- If you are not going to act, do not imply you might. Ending an interaction with "you're fine,
  carry on" is the correct close for the common case.

### If the player appeals

An appeal is about the **stored evidence report**, so make sure it exists and is complete before you
discuss anything.

1. **Hand over the artefact.** The stored assessment — the verdict, the four numbers, the sample size,
   the independent signals, and the signed contributions — is the basis of any action and the thing
   the appeal is against. Use `/xray inspect` and `/xray history` to reproduce it, and
   `/xray note` to record the appeal and its outcome on the record.
2. **Re-run the workflow against the record, not your memory of it.** Steps 2–8 above, on the stored
   numbers. Appeals often succeed because new context appears — a known cave system, a friend's
   tunnel they followed, a lucky seed — that explains the pattern.
3. **Separate the two questions.** "Was the evidence correctly computed?" is one question; "does the
   evidence justify what we did?" is another. An appeal can win on either. A genuine cave explorer
   with a correctly computed score still should not have been banned — the same statistic, read
   properly, exonerates them.
4. **Involve the administrator if the outcome changes a policy**, or if a pattern of appeals suggests
   the priors are wrong for your world (`docs/ADMIN_GUIDE.md`, §10). Fixing that is a configuration
   change and benefits every player.
5. **Log the outcome.** The note becomes part of the record, and it is what protects the next
   moderator — and the player — the next time this comes up.

If, on review, the evidence does not support what happened, say so plainly and reverse it. That is
the process working, not failing.

---

## 6. Limitations you should know

- **Statistical evidence is not proof.** The system produces priors and likelihood ratios, not
  certainties. It is a tool to focus your attention, not to reach conclusions for you.
- **A low score is not a clean bill of health.** The model is about ore-directed behaviour only. It
  says nothing about flight, combat, reach or any other cheat.
- **A high score is not guilt.** It means a human should look. That human is you, and sometimes the
  answer is "honest player".
- **Exploration and strip mining are legitimate** and the model is built to expect them — the mix
  component rewards cave explorers and the targeting weights are set so strip miners are not flagged
  for walking straight. If these are producing verdicts on your server, the priors need tuning before
  anyone is actioned.
- **Insufficient evidence is not a suspicion.** It means the system cannot say anything yet, and it
  is telling you so honestly rather than guessing.
- **The model can be wrong in the player's favour, too.** The bounded excavation ledger forgets old
  provenance and reports natural terrain or `UNKNOWN` rather than guessing, and a very long session
  gradually becomes more lenient as its buffers overflow. Both errors are deliberate: the system
  would rather miss than falsely accuse.
