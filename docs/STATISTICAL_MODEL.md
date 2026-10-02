# Statistical Model

This document describes the statistical interpretation of the X-ray anti-cheat core: what the
random variables are, what the model assumes, how independent evidence is kept from being
double-counted, how false positives are controlled, and what the verdict looks like on two worked
examples. The mathematics itself is derived in `MATHEMATICAL_MODEL.md`; this document is about
what those equations *mean*, where they can be wrong, and how much confidence they deserve.

The engine is a per-player, per-world, per-window Bayesian accumulator in log-odds space. Every
component produces a log-likelihood ratio; the engine sums them after reliability weighting,
sample-size shrinkage and time decay, adds a prior, and bands the posterior onto a forensic scale.
The decision layer then applies policy to that band. Statistics and enforcement are deliberately
separate: the engine answers "how strongly does this behaviour favour the ore-vision hypothesis?",
and the policy answers "and therefore what?".

---

## 1. Hypotheses

| | $H_0$: legitimate mining | $H_1$: ore-informed mining |
| --- | --- | --- |
| Hidden-discovery rate per block | $\lambda_0$ (per-ore prior) | $\lambda_1 = m\lambda_0$ |
| Discovery mix (buried fraction) | $0.55$ | $0.95$ |
| Movement aligned with ore | $0.5$ (shipped) | $0.8$ (shipped) |
| Look aligned with ore through rock | $0.067$ | $0.5$ |
| Waiting times between finds | $\mathrm{Exp}(r_0)$ | $\mathrm{Exp}(r_1)$ |

$H_1$ is specifically *ore-directed* cheating. It makes no prediction about flight, combat,
reach or anything else; a cheater who is not ore-directed is outside this model. This matters for
interpreting a low score: it is not a clean bill of health.

---

## 2. Random variables

Within one analysis window, scoped to one player and one world:

| Variable | Type | Distribution under $H_0$ | Under $H_1$ |
| --- | --- | --- | --- |
| $K_{\text{ore}}$ | count of hidden discoveries of ore *ore* | $\mathrm{Poisson}(\lambda_0 E)$ | $\mathrm{Poisson}(m\lambda_0 E)$ |
| $K_{\text{hid}}$ | count of hidden discoveries (all ores) | sum of the above | sum of the above |
| $X_j$ | effort (blocks) between successive hidden discoveries | $\mathrm{Exp}(r_0)$ | $\mathrm{Exp}(r_1)$ |
| $K_{\text{mix}}$ | buried discoveries out of $n$ classified | $\mathrm{Bin}(n,p_0)$ | $\mathrm{Bin}(n,p_1)$ |
| $K_{\text{move}}$ | veins approached with movement within $30^\circ$ | $\mathrm{Bin}(n,0.5)$ | $\mathrm{Bin}(n,0.8)$ |
| $K_{\text{look}}$ | veins approached with view within $30^\circ$ | $\mathrm{Bin}(n,0.067)$ | $\mathrm{Bin}(n,0.5)$ |
| $L,\ \text{straightness}$ | path shape summaries | deterministic given path | deterministic given path |

$E$ is the exposure in **blocks mined**, drawn from `PlayerAnalysisWindow.blocksMined()`. It is a
covariate, not a random variable in the inference.

Two structural points about the variables:

- The unit of observation is the **vein/discovery**, never the block. The blocks of one vein are
  placed together by world generation and are strongly correlated; counting them separately would
  be pseudo-replication and would inflate every significance figure.
- The rate and waiting variables are two views of **one** Poisson process. They are treated as one
  independent group, not two.

---

## 3. Model assumptions

1. **Poisson process.** Hidden discoveries occur as independent rare events at a rate proportional
   to blocks mined. Defensible because ore is (approximately) uniformly distributed through mined
   rock and because the exposure axis is effort, not time.
2. **Exponential gaps.** Given (1), inter-arrival effort is exponential. The two models share
   assumptions rather than competing.
3. **Binomial independence.** Alignment and mix trials are independent Bernoulli trials. The
   engine enforces one trial per vein.
4. **Known exposure.** $E$ is measured, not inferred. The rate penalty term depends on it, and a
   dishonest exposure figure would shift the result.
5. **Homogeneous rate within a profile.** Each ore has one legitimate rate and one informed rate
   for the whole window. Real rate varies with depth, biome, terrain and strategy.
6. **Fixed priors.** $\lambda_0$, $m$, $p_0$, $p_1$ and the alignment probabilities are
   configuration constants, not learned parameters.
7. **Additive log-odds.** Independent evidence combines by summation.
8. **Exponential decay is memoryless** and independent of the observation's content.
9. **One world per window.** Nether and overworld distributions are never pooled.

**Limitations of the assumptions.** Assumption 5 is the weakest: a player who starts a session in
rich terrain and ends it in barren stone has a time-varying rate, and a single window average is a
misspecification. Assumption 3 is only as good as the exposure classifier that decides which veins
were "hidden"; a misclassification (a vein actually visible through a gap the classifier missed) is
a contaminated trial that pushes toward suspicion. Assumption 7 holds only if the components are
genuinely capturing distinct information, which is why correlated components are grouped (§4). None
of these assumptions is verified against labelled data; they are plausible modelling positions, and
the conservative priors are the main defence against the ones that are wrong.

---

## 4. Evidence independence and grouping

Bayesian updating by summation is valid only for **independent** pieces of evidence. The engine
makes this explicit: each contribution declares an `independentGroup`, and components measuring the
same underlying signal declare the same group.

| Group | Components | Why grouped |
| --- | --- | --- |
| `discovery-rate` | `hidden-discovery-rate`, `inter-discovery-waiting` | Both are views of one Poisson process. When the intervals partition the exposure, their raw LLRs are *algebraically equal* (§3.3 of `MATHEMATICAL_MODEL.md`). |
| `targeting` | `ore-targeting` | Movement and look alignment are measured at the same moment from the same approach; they are not independent draws. |
| `exposure-mix` | `exposure-mix` | A distinct signal (proportion, not rate), but correlated with the rate signal. |
| `geometry` | `tunnel-geometry` | Path shape; deliberately weak. |

Within a group, weighted LLRs are **summed**; the number of groups $G$ is the count of groups with
at least one informative contribution. The engine then requires $G\ge\texttt{minimumIndependentGroups}=2$
before any conclusion is allowed.

The design's central anti-evasion principle is visible here: *one signal, however strong, is not
enough.* Any single signal can be evaded — a rate-cheater can slow down, a timing-cheater can add
random pauses, a camper can fake their aim — but evading several in a mutually consistent way is
much harder. Requiring two independent families makes a single-signal evasion insufficient.

**Honest caveat on independence.** The groups are *claimed* independent, not proven so. The rate,
mix and targeting signals all derive from the same set of ore encounters, so they are less
independent in practice than the grouping assumes; a single unusual session-family could move all
of them together. The grouping prevents the crudest double-counting (the rate/waiting pair and the
move/look pair) but it is not a formal decorrelation.

---

## 5. Sample size is the maximum, not the sum

The engine computes, per group, the largest sample any component in that group offers, and then
takes the **maximum across groups**:
$$n = \max_{g}\ \max_{i\in g} n_i .$$

Why maximum and not sum. Every family is fed by the same underlying events — the same handful of
ore discoveries is counted once by the rate component, once by the waiting component, and once by
the exposure-mix component. Summing them would triple-count the evidence base and let a player with
six discoveries clear a ten-observation threshold. The maximum states the honest number: *this is
how many independent observations we actually have about this player's behaviour.*

The consequence is deliberate conservatism: even with four corroborating families, a player with
ten real discoveries has $n=10$, which is exactly the minimum. The sample-size threshold cannot be
gamed by adding correlated signals. The geometric component reports $n=1$ by construction, so it
can never inflate the count.

---

## 6. Multiple testing and false-positive control

The system runs an independent assessment per player, per window, per world, and — because
assessment is triggered repeatedly over time — many assessments per player. On a populated server
this is a very large number of hypothesis tests. **There is no explicit multiplicity correction:
no Bonferroni, no Šidák, no Benjamini–Hochberg, no false-discovery-rate procedure is implemented
anywhere in the code.** Anyone reading the code should know that.

What the design does instead is raise the bar empirically at several points:

1. **A low prior** $\rho=\ln(0.02/0.98)$ so the likelihood ratio, not a default suspicion, must
   move the verdict.
2. **Shrinkage** $s(n)=n/(n+\kappa)$ so small samples cannot move the verdict at all.
3. **Minimum sample and minimum independent groups**, so a result drawn from one signal or a
   handful of observations is labelled `INSUFFICIENT` no matter how extreme.
4. **Confidence gating** at the decision layer: enforcement requires *both* an evidence band *and*
   a confidence floor. A strong ratio on thin data is visible but not actionable.
5. **A deliberately weak movement model and a capped geometry contribution**, which are the two
   largest naïve false-positive sources.
6. **A conservative exposure classifier** that can only ever downgrade a `HIDDEN` verdict to more
   visible, never the reverse.

The crucial interpretive warning, repeated from `MATHEMATICAL_MODEL.md` §8: the bands are labelled
by likelihood ratios ("1000 = very strong") but they are compared against **posterior odds**
including the prior, and a Bayes factor is **not** a $p$-value. "Likelihood ratio 1000" does not
mean "one false positive in 1000"; converting it to an error rate needs a prior and a loss
function that this code does not specify. No empirical false-positive rate is claimed, and none
could be measured without labelled ground-truth data that the project does not have.

**Sequential-testing caveat.** Because each evaluation is a fresh window and the ban-wave candidate
keeps the **peak** assessment ever seen (not the latest, and not a running average), repeatedly
evaluating the same player raises the chance that at least one window reaches a high band — a
garden-of-forking-paths problem across time. The 14-day candidate TTL and the 24-hour wave interval
bound it, but there is no alpha-spending or sequential-probability-ratio construction. This is a
genuine, unmitigated multiplicity across evaluations, and it argues for treating the score as a
*prioritisation* signal for human review rather than as an automated verdict.

---

## 7. Sequential evidence accumulation and decay

For a single assessment the accumulation is a fixed formula (`MATHEMATICAL_MODEL.md` §4). Across
assessments the system is genuinely sequential:

- **Each window is scored independently** from its own observations.
- **`BanWaveCandidate` keeps the peak**, per dimension: `mergedWith` takes the maximum suspicion
  score, the maximum confidence, the maximum sample size and groups, and the strongest band;
  `firstDetected` is preserved so the record shows how long the player has been a candidate. A
  quiet session never erases a strong earlier one, and a cheater cannot clear their record by
  behaving for a day.
- **Decay applies within a window**, via the mean weight $\phi$ of its discoveries, and again
  implicitly across windows because an old window's evidence simply is not re-collected.

Decay is exponential, $w(t)=e^{-\lambda t}$ with $\lambda=\ln 2/168$ per hour, chosen for
memorylessness and as the conjugate of the Poisson process. At one half-life a discovery carries
half its weight; at two, a quarter. The effect on a verdict is monotone and smooth — no cliff — so
a score cannot be manipulated by pausing until an arbitrary cut-off.

The peak-keeping and the decay pull in opposite directions and are not reconciled by any
principled rule: a player who was flagged once six months ago and has been quiet since keeps their
peak band only until the TTL expires, after which the candidate is dropped and the record resets.
A player who was flagged once and then banned in the next wave is caught; a player who was flagged
once and drifts below threshold for fourteen days is discarded entirely. This is a policy choice,
not a statistically optimal one.

---

## 8. Uncertainty propagation

The model's treatment of uncertainty is mostly through the confidence index $C$ (§7 of
`MATHEMATICAL_MODEL.md`) and the shrinkage factor, but there are specific places where missing
information has a stated direction of error.

**Exposure state `UNKNOWN`.** When the world state around an ore cannot be established — an
unloaded neighbour chunk, pruned block history, or a modification by another plugin that cannot be
dated — `ExposureAnalyzer` returns `ExposureState.UNKNOWN` with confidence $0$. The *intent*,
documented on the enum, is neutral treatment: `hiddennessWeight(UNKNOWN) = 0.5`, sitting between
`PARTIALLY_EXPOSED` ($0.25$) and `HIDDEN` ($1.0$), so that pruning history neither penalises nor
credits the player.

**`UNKNOWN` exposure is excluded, not credited.** When the world state around an ore cannot be
established — an unloaded neighbour chunk, pruned block history, or a modification by another plugin
that cannot be dated — `ExposureAnalyzer` returns `ExposureState.UNKNOWN` with confidence $0$. Such a
discovery is excluded from the exposure-mix signal entirely: the component counts a discovery as
hidden only when `isUnaccountablyHidden()` holds and as exposed only when `isVisibleByOrdinaryPlay()`
holds, so `UNKNOWN` — and `CONDITIONALLY_EXPOSED` — fall outside both counts. The denominator is the
number of *classified* discoveries, so pruning world history leaves the measured hidden fraction
untouched rather than drifting it toward $H_0$. The enum's `hiddennessWeight()` table still documents
the intended ordering ($\text{UNKNOWN} = 0.5$ between `PARTIALLY_EXPOSED` and `HIDDEN`), but the two
predicates above are the mechanism the component actually uses.

**Origin attribution is carried by the exposure state, not by a second multiplier.** `OreDiscovery`
still carries an `evidenceDiscount` field, but the discounting it was designed to express is already
applied upstream: ore reached through a tunnel dug by another player is classified
`CONDITIONALLY_EXPOSED` rather than `HIDDEN`, and therefore never enters the buried-discovery count
that drives the discovery-rate and waiting-time models. Applying an additional multiplier inside the
components would discount the same fact twice. No component reads `evidenceDiscount`; it is retained
for the stored record and for the visualiser's minimum-confidence filter.

**Within the components, missing data is handled by silence, not by imputation.** No blocks mined
$\Rightarrow$ the rate component is non-informative. No hidden discoveries $\Rightarrow$ rate and
waiting are non-informative (not exculpatory). No measurable approach $\Rightarrow$ targeting is
non-informative. An empty window has $\phi=1$ but no contributions. "No data" is never turned into
"evidence of innocence", which is the right call: a signal that has not fired is not a signal that
fired negatively.

---

## 9. Ban-wave methodology

Immediate enforcement has three problems the design is built to avoid: it removes the cheater while
their technique still works, so they report what tripped it and the community learns the threshold;
it acts on one moment rather than a body of evidence; and it produces a trickle of individually
attributable bans.

**Defaults** (`BanWavePolicy.defaults()`):

| Parameter | Default | Role |
| --- | --- | --- |
| `enabled` | true | batched enforcement used at all |
| `intervalMinutes` | 1440 (24 h) | minimum time between waves |
| `candidateTtlHours` | 336 (14 days) | how long a candidate's evidence stays eligible |
| `minimumStrength` | `STRONG` | band a candidate must have reached |
| `minimumConfidence` | 0.85 | confidence floor per candidate |
| `minimumIndependentSignals` | 2 | independent families per candidate |
| `minimumCandidates` | 2 | a wave of one is just a delayed immediate ban |
| `automaticBan` | false | waves propose a list; a human approves |

**Flow.** `BanWavePlanner.consider` adds or strengthens a candidate when a snapshot meets the wave
thresholds. `pruneExpired` drops candidates older than the TTL from first detection. `plan` runs a
wave only when the mode is `BAN_WAVE`, batching is enabled, at least `intervalMinutes` have elapsed
since the last wave, and at least `minimumCandidates` eligible candidates survive pruning and the
strength/confidence/signals filters. The plan is inert data (`BanWavePlan`): it can be logged,
shown, and audited before anything happens to anybody.

**Statistical reading of this.** A ban wave is a **precision-management device**, not a
significance test. Batching does three useful things statistically: it delays enforcement until
several independent cases exist (so a single borderline assessment is unlikely to be the sole basis
for a ban), it lets the assessment be recomputed from persisted observations immediately before
enforcement (guarding against acting on stale or buggy scores), and it hides from the community
which behaviour tracked which player. It does **not** correct for multiple testing, and the peak
semantics of `BanWaveCandidate` (keeping the strongest band ever seen) is the one place where
temporal multiplicity actively works against precision (§6).

**Decision layer defaults** (`DecisionPolicy.defaults()`) are separate and more conservative: mode
`ALERT_ONLY`, alert floor confidence $0.5$ and band `MODERATE`; `FLAG` at `STRONG`; `KICK` and
`BAN` at `STRONG`/`VERY_STRONG` with confidence floor $0.75$. A mode can only ever *downgrade* an
action — `ALERT_ONLY` reduces a human-visible action (`KICK`, `BAN`) to an alert, `BAN_WAVE`
converts a `BAN` into `CANDIDATE` — so a misconfigured mode cannot make enforcement easier than the
evidence allows.

Note the deliberate difference between the two layers: `DecisionPolicy.defaults()` is `ALERT_ONLY`,
which is the least-acting mode and therefore the right safety net when a configuration omits the key,
whereas the shipped `config.yml` sets `suspicion.enforcement-mode: BAN_WAVE` so that candidates actually
accumulate. `BAN_WAVE` still converts a `BAN` into a `CANDIDATE` rather than acting, and the shipped
`ban-wave.automatic-ban` is `false`, so nothing is enforced without a moderator's approval. The worked
examples below quote the policy default, which is why they say `ALERT_ONLY`.

---

## 10. Symbol table

| Symbol | Meaning | Default |
| --- | --- | --- |
| $H_0/H_1$ | legitimate mining / ore-informed mining | — |
| $E$ | exposure, blocks mined | — |
| $K$ | observed hidden count | — |
| $\lambda_0,\lambda_1$ | hidden-discovery rate per block | $\lambda_1=m\lambda_0$ |
| $m$ | informed rate multiplier | diamond 6, emerald 6, ancient debris 4 |
| $X_j$ | inter-discovery effort | — |
| $r_0,r_1$ | exponential rates | per-ore |
| $p_0,p_1$ | Bernoulli probabilities | mix $0.55/0.95$; move $0.5/0.8$; look $0.067/0.5$ |
| $n$ | independent sample size | max across groups |
| $G$ | number of independent groups | up to 4 |
| $\mathrm{LLR}$ | log-likelihood ratio | — |
| $r_i$ | component reliability | $0.8,0.7,0.75,0.6,0.4$ |
| $\rho$ | prior log-odds | $\ln(0.02/0.98)\approx-3.8918$ |
| $S_g,R$ | group sum / raw total | — |
| $\kappa$ | shrink constant | 5 |
| $s(n)$ | $n/(n+\kappa)$ | — |
| $\lambda$ | decay constant per hour | $\ln 2/168\approx0.004126$ |
| $\phi$ | mean window decay | $(0,1]$ |
| $\Delta$ | effective log-odds $Rs\phi$ | — |
| $P$ | posterior log-odds, clamped | $[-30,30]$ |
| $\pi$ | suspicion score $\sigma(P)$ | $(0,1)$ |
| $C$ | statistical confidence | $(0,1)$ |
| $n_0,g_0$ | confidence scales | 20, 2 |
| $\theta$ | alignment cone half-angle | $30^\circ$ |

---

## 11. Worked numerical examples

Both examples are computed with the shipped defaults: $\kappa=5$, $n_0=20$, $g_0=2$,
$t_{1/2}=168$ h, diamond $\lambda_0=1.5\times10^{-3}$ per block, $m=6$, look model $0.067/0.5$,
move model $0.5/0.8$ (shipped), mix model $0.55/0.95$, shrinkage and no elapsed decay ($\phi=1$).

### 11.1 Legitimate scenario — productive strip-miner

One window, overworld diamond: $E=4000$ blocks mined, $6$ hidden diamond discoveries, $4$ exposed
(so $n_{\text{mix}}=10$, observed hidden fraction $0.60$), $5$ measurable approaches of which $2$
had aligned movement and $0$ aligned look, composite path linearity $L=0.60$.

| Component | Group | Raw LLR | $n_i$ | $r_i$ | Weighted |
| --- | --- | --- | --- | --- | --- |
| hidden-discovery-rate | discovery-rate | $\;6\ln 6-0.0075\cdot4000=-19.249$ | 6 | 0.8 | $-15.400$ |
| inter-discovery-waiting | discovery-rate | $-19.249$ | 6 | 0.7 | $-13.475$ |
| exposure-mix | exposure-mix | $\;6\ln\tfrac{0.95}{0.55}+4\ln\tfrac{0.05}{0.45}=-5.510$ | 10 | 0.6 | $-3.306$ |
| ore-targeting | targeting | move $-1.809$, look $-3.116$ $\Rightarrow -4.928$ | 5 | 0.75 | $-3.696$ |
| tunnel-geometry | geometry | $\mathrm{clamp}(1.2(0.60-0.75))=-0.180$ | 1 | 0.4 | $-0.072$ |

Group sums: discovery-rate $-28.874$, exposure-mix $-3.306$, targeting $-3.696$, geometry $-0.072$,
so $R=-35.948$. Sample size $n=\max(6,6,10,5,1)=10$, groups $G=4$. Shrinkage
$s=10/15=0.667$; $\Delta=-23.965$; $P=\mathrm{clamp}(-3.892-23.965)=-27.857$ (not clamped);
$\pi\approx2\times10^{-13}$ atoms; confidence
$C=(1-e^{-0.5})(1-e^{-2})=0.393\times0.865=0.340$.

**Verdict.** $P=-27.857<\ln 3$, and $n=10\ge10$, $G=4\ge2$, so the gate passes but the band floor
does not: **`INSUFFICIENT`**. Under the policy default (`ALERT_ONLY`) the strength is below
`MODERATE`, so `DecisionEngine` returns `NONE` — nothing is even reported. A competent, productive
legitimate miner produces strongly *negative* evidence, and the low confidence ($0.34$) correctly
labels the observation base as thin.

### 11.2 Cheating scenario — moderate ore-vision user

One window, overworld diamond: $E=2000$ blocks mined, $16$ hidden diamond discoveries, $2$ exposed
($n_{\text{mix}}=18$, observed hidden fraction $0.889$), $16$ measurable approaches of which $11$
had aligned movement and $9$ aligned look, composite path linearity $L=0.89$.

| Component | Group | Raw LLR | $n_i$ | $r_i$ | Weighted |
| --- | --- | --- | --- | --- | --- |
| hidden-discovery-rate | discovery-rate | $\;16\ln 6-0.0075\cdot2000=13.668$ | 16 | 0.8 | $10.935$ |
| inter-discovery-waiting | discovery-rate | $13.668$ | 16 | 0.7 | $9.568$ |
| exposure-mix | exposure-mix | $\;16\ln\tfrac{0.95}{0.55}+2\ln\tfrac{0.05}{0.45}=4.350$ | 18 | 0.6 | $2.610$ |
| ore-targeting | targeting | move $0.589$, look $13.723$ $\Rightarrow 14.311$ | 16 | 0.75 | $10.733$ |
| tunnel-geometry | geometry | $\mathrm{clamp}(1.2(0.89-0.75))=0.168$ | 1 | 0.4 | $0.067$ |

Group sums: discovery-rate $20.502$, exposure-mix $2.610$, targeting $10.733$, geometry $0.067$,
so $R=33.913$. Sample size $n=\max(16,16,18,16,1)=18$, groups $G=4$. Shrinkage
$s=18/23=0.783$; $\Delta=26.541$; $P=\mathrm{clamp}(-3.892+26.541)=22.649$ (not clamped);
$\pi\approx1-1.5\times10^{-10}$; confidence
$C=(1-e^{-0.9})(1-e^{-2})=0.593\times0.865=0.513$.

**Verdict.** $P=22.649\ge\ln 1000$, $n\ge10$, $G\ge2$, so the band is **`VERY_STRONG`** (about 98
decibans). At the decision layer the alert gate passes ($C=0.513\ge0.5$), but the confidence floor
for irreversible action ($0.75$) is **not** met: `strongestJustifiedAction` returns `FLAG`, not
`BAN`. Under the policy default (`ALERT_ONLY`) a flag is not player-visible, so the outcome is `FLAG` —
the assessment is recorded for a moderator, and nobody is banned. To reach the ban-confidence floor
the window needs $n\ge41$, at which point a genuinely decisive case becomes a ban-wave candidate
(or, in `ALERT_ONLY`, an alert).

**Reading the two examples together.** The evidence arithmetic is decisive in the cheating case and
strongly exculpatory in the legitimate one, and the confidence index is low in both — $0.34$ and
$0.51$ — because the strongest single family has only $10$ and $18$ observations respectively. That
is the design working as intended: the model distinguishes the two behaviours sharply while
refusing to claim certainty, and the two-gate policy lets a human see the difference between "this
argues strongly for cheating" and "we have enough evidence to act".

---

## 12. Limitations and known approximations

Beyond the per-assumption limitations in §3, and the code-level approximations catalogued in
`MATHEMATICAL_MODEL.md` §10, the statistical limitations that most affect interpretation are:

1. **No calibrated error rate.** Nothing in the system can currently state a false-positive
   probability. The bands are Bayes-factor labels, not error guarantees, and no labelled dataset
   exists to validate them.
2. **No multiplicity control across players or across time** (§6). The peak-keeping candidate
   semantics is the sharpest edge of this.
3. **Priors are beliefs.** A server whose ore distribution differs materially from the defaults
   will see miscalibrated rates; the model tolerates small errors but not order-of-magnitude ones.
4. **Independence is asserted, not measured.** Groups reduce the crudest double-counting but the
   underlying signals share events.
5. **The confidence index is heuristic**, a product of saturating exponentials with hand-set
   scales; it is monotone in sample and groups, which is what matters operationally, but it is not
   a probability.
6. **Separately-published quantities are not jointly meaningful.** The rate and waiting LLRs, the
   per-ore average, and the mix and targeting LLRs are each defensible alone; their unweighted
   comparison to one another is not, because they scale differently with sample size.
7. **`UNKNOWN` is excluded rather than credited** (§8): a discovery whose exposure could not be
   determined counts neither as hidden nor as exposed, so missing world data cannot argue either for or
   against the player. The origin discount exists in the model and is carried on every discovery, but
   the listener currently always sets it to $1.0$, so it is not yet doing work.
8. **The session seam is dropped, not modelled.** When stored history is merged with the live session,
   the interval that spans the boundary has an unknowable length — the server may have been down, or the
   player may have played unrecorded — so that one observation is discarded rather than estimated. This
   is always the lenient direction for the waiting-time LLR, but it is a real asymmetry: a player whose
   evidence arrives in many short sessions loses one interval per assessment, while a player with the
   same conduct in one long session keeps all of theirs. Only the boundary is affected; every interval
   wholly inside the stored history or wholly inside the session is intact.
9. **A null result is not exoneration.** The model tests ore-directed cheating only. A player who
   is cheating in some other way, or who is ore-aware but clever enough to defeat all five
   components simultaneously, produces a low score.

---

## 13. False-positive risk

The design intent is asymmetric: a false accusation costs far more than a missed detector, so
every ambiguous case resolves toward "not enough evidence". The concrete mechanisms are the low
prior, shrinkage, minimum sample and group gates, the two-gate decision policy, the deliberately
weak movement model, the capped geometry contribution, the conservative exposure classifier, the
right-censored trailing interval (which removes a bias toward suspicion), and the negative
evidence returned by the exposure-mix component for cave explorers.

None of these makes the system sound. The residual risks a reader should hold in mind are:

- **Thin-data overreach.** A `VERY_STRONG` band on $n=18$ has confidence $0.51$; the two-gate
  policy prevents a ban but the band label is still eye-catching, and a server that lowers the
  confidence floor without understanding the distinction would ban on thin evidence.
- **Terrain mismatch.** The priors assume vanilla-like distributions; amplified, modded, or
  custom-ore worlds can invalidate them, and the failure mode is either missed cheaters or false
  positives depending on the direction of the mismatch.
- **Temporal multiplicity.** Peak-keeping makes a long-observed honest-but-unlucky player
  progressively more likely to have at least one high-scoring window.
- **Silent approximation.** The `UNKNOWN` and origin-discount paths that are not wired in mean a
  server with aggressive pruning or cross-plugin terrain edits will not behave exactly as the
  design documents say.
- **Overreading the scale.** "Likelihood ratio 1000" is not "one in a thousand"; treating the
  forensic labels as error rates is the single most likely conceptual mistake by an operator.

The defensible position is that this is a **prioritisation and explanation system**, not an oracle:
it surfaces players whose behaviour is hard to explain under a documented model of legitimate play,
it states exactly what each signal measured and how much data backed it, and it leaves the
consequence to a policy a human chose. Used that way, with the defaults (alert-only, confidence
floors, minimum groups), the false-positive risk is bounded by policy rather than by the
mathematics — and the mathematics does not claim otherwise.
