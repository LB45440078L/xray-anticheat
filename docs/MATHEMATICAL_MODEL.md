# Mathematical Model

This document derives the mathematics that the X-ray anti-cheat core actually implements.
Every equation below corresponds to a named method or field in the source; where the
implementation is an approximation, a heuristic, or diverges from an accompanying comment,
that is stated explicitly rather than smoothed over.

It is written to be read alongside the code. The principal sources are:

| Concern | Source file |
| --- | --- |
| Likelihood ratios | `xray-core/.../statistics/LikelihoodRatios.java` |
| Log-odds arithmetic | `xray-core/.../statistics/LogOdds.java` |
| Poisson count model | `xray-core/.../statistics/PoissonDistribution.java` |
| Exponential waiting model | `xray-core/.../statistics/ExponentialDistribution.java` |
| Binomial inference, Clopper–Pearson, Wilson | `xray-core/.../statistics/BinomialModel.java` |
| Special functions (log-gamma, log-beta, incomplete beta) | `xray-core/.../statistics/SpecialFunctions.java` |
| Global parameters | `xray-core/.../evidence/EvidenceParameters.java` |
| Accumulation, grouping, shrinkage, decay | `xray-core/.../evidence/EvidenceEngine.java` |
| Strength bands | `xray-core/.../evidence/EvidenceStrength.java` |
| The five components | `xray-core/.../evidence/component/*.java` |
| Per-ore priors | `xray-core/.../config/OreProfile.java`, `MapOreProfileRegistry.java` |
| Trajectory features | `xray-core/.../analysis/TrajectoryAnalysis.java` |
| PCA / Jacobi | `xray-core/.../geom/PrincipalAxes.java` |
| Exposure classification | `xray-core/.../world/ExposureAnalyzer.java` |

The companion document `STATISTICAL_MODEL.md` covers the statistical interpretation,
assumptions, limitations and worked numerical examples. This document is the derivation.

---

## 1. Notation

The engine accumulates belief in **natural-log-odds space**. A table of the symbols used
throughout both documents:

| Symbol | Meaning | Default / value in code |
| --- | --- | --- |
| $H_0$ | Null hypothesis: legitimate mining | — |
| $H_1$ | Alternative hypothesis: ore-informed (X-ray) mining | — |
| $K$ | Observed count of hidden-ore discoveries | — |
| $E$ | Exposure, measured in **blocks mined** | — |
| $\lambda_0$ | Expected count rate per block under $H_0$ | per-ore, e.g. $1.5\times10^{-3}$ for diamond |
| $\lambda_1$ | Expected count rate per block under $H_1$ | $\lambda_0 \times$ `oreInformedRateMultiplier` |
| $m$ | Ore-informed rate multiplier | diamond $6.0$, emerald $6.0$, ancient debris $4.0$ |
| $x$ | A waiting-time interval (in blocks of effort) | — |
| $r_0, r_1$ | Exponential rates under $H_0$, $H_1$ | per-ore, per block |
| $n$ | Sample size (independent observations) | — |
| $k$ | Number of successes/counts (context-dependent) | — |
| $p_0, p_1$ | Success probabilities under $H_0$, $H_1$ | per-ore or configured |
| $\mathrm{LLR}$ | Log-likelihood ratio, $\ln\!\big[P(\text{data}\mid H_1)/P(\text{data}\mid H_0)\big]$ | — |
| $r_i$ | Reliability of component $i$ | $0.8,0.7,0.75,0.6,0.4$ |
| $\rho$ | Prior log-odds | $\ln(0.02/0.98) \approx -3.8918$ |
| $g$ | Independent-group index; $G$ = number of groups | up to 4 |
| $S_g$ | Sum of weighted LLRs within group $g$ | — |
| $R$ | Raw summed log-odds, $R=\sum_g S_g$ | — |
| $\kappa$ | Sample-size shrink constant (`sampleSizeShrinkConstant`) | $5$ |
| $s(n)$ | Shrinkage factor $n/(n+\kappa)$ | — |
| $\lambda$ | Time-decay constant (per hour) | $\ln 2 / 168$ |
| $\phi$ | Mean decay weight over the window | $\in(0,1]$ |
| $\Delta$ | Effective (post-shrink, post-decay) log-odds | — |
| $P$ | Posterior log-odds, $P=\rho+\Delta$ | clamped to $[-30,30]$ |
| $\pi$ | Suspicion score, $\sigma(P)$ | $\in(0,1)$ |
| $C$ | Statistical confidence | $\in(0,1)$ |
| $n_0, g_0$ | Confidence scales | $20$, $2$ |
| $\theta$ | Cone half-angle for alignment | $30^\circ$ |
| $\mathbf{C}$ | $3\times3$ covariance matrix of a point cloud | — |
| $\ell_1\!\ge\!\ell_2\!\ge\!\ell_3$ | Eigenvalues of $\mathbf{C}$ | — |

Two naming cautions: the code uses `k` both for the shrink constant and for counts; here the
shrink constant is written $\kappa$. The variable the geometry component calls `linearity`
(`0.5*straightness + 0.5*pathEfficiency`) is **not** `PrincipalAxes.linearity()`; they are
different quantities and are named distinctly below.

---

## 2. Hypotheses

The model is a two-hypothesis likelihood-ratio test evaluated per player, per world, per
analysis window.

**$H_0$ — legitimate mining.** The player discovers hidden ore at the rate a competent,
honest miner would, given the terrain and the amount of rock moved. Their direction of travel
and camera aim are governed by ordinary exploration and mining geometry, not by knowledge of
where ore is. Their mix of buried and exposed discoveries matches the server's baseline.

**$H_1$ — ore-informed mining.** The player has access to information about ore positions they
could not have obtained by play (X-ray, ore ESP, or equivalent). This lets them find buried
ore at an elevated rate per block moved, biases their arriving heading and aim toward ore
they cannot yet see, and skews their discovery mix toward buried ore. It does not,
by itself, predict any particular tunnel shape.

$H_1$ is deliberately narrow: it is *ore-directed* cheating, not cheating in general. A player
who flies, or uses killaura, is not modelled here and will not raise this score.

---

## 3. Likelihood ratios

Each observation produces a log-likelihood ratio
$$\mathrm{LLR} = \ln\frac{P(\text{data}\mid H_1)}{P(\text{data}\mid H_0)}.$$
Positive favours $H_1$, negative favours $H_0$, zero is uninformative.

### 3.1 Poisson count likelihood ratio

**Method:** `LikelihoodRatios.poissonCount(observed, exposure, lambda0, lambda1)`.

Under $H_0$ the hidden-discovery count over exposure $E$ is $K\sim\mathrm{Poisson}(\lambda_0 E)$;
under $H_1$, $K\sim\mathrm{Poisson}(\lambda_1 E)$. The log mass function is
$$\ln p(k;\lambda E) = k\ln(\lambda E) - \lambda E - \ln k!.$$
Subtracting the $H_0$ log mass from the $H_1$ log mass, the $\ln k!$ terms cancel because both
hypotheses share the same observation space:
$$\boxed{\;\mathrm{LLR} = k\ln\!\frac{\lambda_1}{\lambda_0} - (\lambda_1-\lambda_0)E\;}$$

Each symbol: $k$ is the observed hidden count, $E$ is exposure in blocks mined, and
$\lambda_0,\lambda_1$ are per-block rates. The second term is a penalty: it grows with exposure
regardless of the count, so a large amount of mining with a proportionate number of finds is
*not* treated as suspicious. The LLR is zero at
$$k^\star = E\,\frac{\lambda_1-\lambda_0}{\ln(\lambda_1/\lambda_0)},$$
i.e. at a rate of $(\lambda_1-\lambda_0)/\ln(\lambda_1/\lambda_0)$ per block. For the default
diamond profile ($\lambda_0=0.0015$, $m=6$) this is $0.0075/1.7918 = 0.00419$ per block, or
$4.19$ per 1000 blocks — about $2.79\times$ the legitimate prior. Only a rate above that
crossover produces positive evidence.

**Why exposure is blocks mined, not time.** Exposure is $E=\texttt{window.blocksMined()}$, not
elapsed seconds. Wall-clock time is confounded by mining speed: haste effects, tool quality,
AFK pauses, lag and simply playing longer. "Finds per block of rock moved" is a property of the
terrain and the player's strategy and is comparable between a player with an Efficiency V
pickaxe and one without. Time enters the model only as evidence *decay* (§5), never as the
measure of effort. The implementation returns $0$ when $E=0$ ("no exposure, no rate
information") and the component returns a **non-informative** contribution (LLR $=0$) when no
hidden discoveries were recorded, rather than the large negative value that a zero count against
a positive expectation would produce — crediting a player for not having been observed yet would
be wrong.

### 3.2 Binomial likelihood ratio

**Method:** `LikelihoodRatios.binomialCount(successes, trials, p0, p1)`.

For $k$ successes in $n$ independent trials, the binomial mass shares the coefficient
$\binom{n}{k}$ across hypotheses, so
$$\boxed{\;\mathrm{LLR} = k\ln\!\frac{p_1}{p_0} + (n-k)\ln\!\frac{1-p_1}{1-p_0}\;}$$

This is used for genuinely binary signals. Two are in the engine:

- **Exposure mix** (`ExposureMixComponent`): of $n$ classified discoveries, $k$ were buried.
  $p_0=$ `legitimateHiddenFraction` $=0.55$, $p_1=$ `oreInformedHiddenFraction` $=0.95$.
- **Targeting** (`OreTargetingComponent`): per buried vein with a measurable approach, did the
  player's movement direction, and separately their view direction, lie within
  `targetingAlignmentThresholdDegrees` $=30^\circ$ of the ore? Each vein contributes one
  Bernoulli trial per signal (movement, look), so the trials count **veins, not blocks**.

The independence of trials is an assumption the caller must uphold. The engine enforces it by
counting one opportunity per vein, never per block, because the blocks of a vein are placed
together by world generation and are strongly correlated. Treating blocks as independent would
be pseudo-replication.

### 3.3 Exponential waiting-time likelihood ratio with right-censoring

**Methods:** `ExponentialDistribution`, `LikelihoodRatios.exponentialIntervals`,
`PlayerAnalysisWindow.hiddenDiscoveryIntervals()`.

If discoveries occur as a Poisson process, the gaps between them are exponential, so this model
shares assumptions with §3.1 rather than competing with it. For an **observed** interval $x$ the
log density is
$$\ln f(x\mid r) = \ln r - r x,$$
and for a **right-censored** interval — one known only to exceed $x$, because the window ended
before the next discovery — the likelihood contribution is the survival function
$$\ln S(x\mid r) = -r x.$$
Summing $H_1$ log terms minus $H_0$ log terms over $n_{\text{obs}}$ observed and any censored
intervals gives
$$\boxed{\;\mathrm{LLR} = n_{\text{obs}}\ln\!\frac{r_1}{r_0} - (r_1-r_0)\,T,\qquad
T=\sum_{\text{obs}}x + \sum_{\text{cens}}x\;}$$

$T$ is the total accumulated effort across all intervals. This is algebraically the same linear
form as the Poisson LLR of §3.1 with $k=n_{\text{obs}}$ and $E=T$; the two components are
therefore **not independent evidence** and are grouped accordingly (§4).

**Why discarding the trailing interval biases toward suspicion.** The final interval — from the
last discovery to the end of the window — is the effort during which no discovery occurred. If it
were dropped, only the "productive" intervals would enter the sum, so the observed mean wait
$\bar{x}=T/n_{\text{obs}}$ would be systematically shortened. A shorter mean wait looks like a
higher discovery rate, which is evidence for $H_1$; dropping the interval would thus manufacture
suspicion. `hiddenDiscoveryIntervals()` therefore appends it explicitly as a right-censored
observation. When there are no discoveries at all, the entire window effort becomes one censored
observation.

---

## 4. Log-odds accumulation

**Methods:** `LogOdds`, `EvidenceEngine.evaluate`, `EvidenceContribution`.

Independence-based Bayesian updating is a product in probability space and a **sum** in log-odds
space:
$$P = \rho + \sum_i w_i, \qquad w_i = r_i\,\mathrm{LLR}_i \cdot s(n)\cdot \phi .$$
The engine splits this into three stages.

**(a) Per-component weighting.** Each component returns `(logLikelihoodRatio, sampleSize,
reliability)`. Its weighted contribution is
$$w_i = r_i \cdot \mathrm{LLR}_i,$$
where $r_i\in[0,1]$ is the model's intrinsic trust in that signal
(`EvidenceContribution.weightedLogLikelihoodRatio`). The shipped reliabilities are:

| Component | `independentGroup` | Reliability $r_i$ |
| --- | --- | --- |
| `hidden-discovery-rate` | `discovery-rate` | $0.8$ |
| `inter-discovery-waiting` | `discovery-rate` | $0.7$ |
| `ore-targeting` | `targeting` | $0.75$ |
| `exposure-mix` | `exposure-mix` | $0.6$ |
| `tunnel-geometry` | `geometry` | $0.4$ |

**(b) Grouping and the raw sum.** Components declare an `independentGroup`; components sharing a
group are measuring one underlying signal. The engine computes, per group,
$$S_g = \sum_{i:\,g(i)=g} r_i\,\mathrm{LLR}_i, \qquad
R = \sum_g S_g .$$
Non-informative contributions (zero sample, zero LLR, or zero reliability) are excluded. A
component that throws is caught and recorded as non-informative with the failure quoted, so one
broken signal cannot deny the player an assessment.

**(c) Shrinkage and decay.** A single scalar shrinkage $s$ and decay $\phi$ are applied to the
whole raw sum, not per component:
$$\Delta = R\,s(n)\,\phi .$$
The posterior is then
$$P = \mathrm{clamp}\!\big(\rho + \Delta,\,-30,\,30\big),$$
and the suspicion score is the logistic transform $\pi=\sigma(P)=1/(1+e^{-P})$. The clamp
(`LogOdds.MAX_MAGNITUDE = 30`) exists so that a long run of evidence cannot overflow `exp`, and
so a single overwhelming observation cannot pin the posterior at exactly $1$ and freeze the model
against later contradicting evidence. Its magnitude corresponds to a probability of about
$1-10^{-13}$ and is far beyond any decision threshold, so it never changes a verdict — it only
keeps the arithmetic finite.

`LogOdds.toProbability` uses branch-stable forms rather than the naive $1/(1+e^{-x})$ to avoid
overflow for large negative $x$ and cancellation for large positive $x$. Decibans
($10\log_{10}$ of the odds) are a display unit only.

---

## 5. Time decay

**Methods:** `EvidenceParameters.decayLambdaPerHour`, `decayWeight`, `EvidenceEngine.meanDecay`.

$$\lambda = \frac{\ln 2}{t_{1/2}},\qquad w(t)=e^{-\lambda t},$$
with the default half-life $t_{1/2}=\texttt{halfLifeHours}=168$ hours (one week), so
$\lambda = 0.0041259$ per hour and $w(168)=0.5$, $w(336)=0.25$.

The weight applied to the window is the **mean** of the per-discovery weights,
$$\phi = \frac{1}{|D|}\sum_{d\in D}e^{-\lambda\,\mathrm{age}(d)},$$
where $\mathrm{age}(d)$ is measured from each discovery's timestamp to the explicitly passed
`evaluatedAt`. An empty window returns $\phi=1$ (nothing to age). Passing `evaluatedAt`
explicitly means replaying a stored window reproduces the historical verdict rather than silently
re-decaying it against the current clock.

**Why the exponential form.** It is the unique continuous decay that is *memoryless*: the
fraction of weight lost in the next hour does not depend on how old the observation already is.
It is also the natural conjugate of the Poisson process that generated the discoveries, so decay
and statistics share an assumption. A hard cut-off would create a cliff where a player's score
drops discontinuously an hour after an event — surprising to administrators and gameable by
pausing for the cut-off to pass.

---

## 6. Sample-size shrinkage

**Method:** `EvidenceParameters.shrinkFactor`.

$$s(n) = \frac{n}{n+\kappa}, \qquad \kappa=\texttt{sampleSizeShrinkConstant}=5 .$$

| $n$ | 1 | 2 | 3 | 5 | 10 | 50 |
| --- | --- | --- | --- | --- | --- | --- |
| $s(n)$ | 0.167 | 0.286 | 0.375 | 0.500 | 0.667 | 0.909 |

A component's nominal LLR is what it would mean if the model were exactly right *and* the sample
were large. With a handful of observations it should not be taken at face value, so it is scaled
toward zero. This is the single mechanism that prevents "three lucky finds" from producing a
dramatic score: a component backed by two observations contributes only 29% of its nominal
weight, while one backed by fifty contributes 91%.

The shrinkage factor is deliberately **not** a p-value correction and carries no coverage
guarantee; it is a regularising prior that the truth is near the null when data are scarce. It is
applied once to the whole sum, and one concerns a genuine statistical subtlety: because it depends
on the *maximum* group sample size (§4 and `STATISTICAL_MODEL.md` §4), a large sample in one
signal family partially "unshrinks" the accumulated evidence of every other family. This is a
deliberate simplification, not an error-corrected estimator.

---

## 7. Statistical confidence

**Method:** `EvidenceParameters.statisticalConfidence`.

$$C(n,G) = \Big(1-e^{-n/n_0}\Big)\Big(1-e^{-G/g_0}\Big),$$
with $n_0=\texttt{confidenceSampleScale}=20$ and $g_0=\texttt{confidenceGroupScale}=2$.

- The first factor is **sample adequacy**: it rises with the number of independent observations
  and saturates. At $n=20$ it is $0.632$; at $n=60$, $0.950$.
- The second factor is **independence adequacy**: it rises with the number of distinct signal
  families that contributed. At $G=2$ it is $0.865$; at $G=4$, $0.982$.

Multiplying the two means plenty of data from one family cannot substitute for independent
corroboration: $n=100$ with $G=1$ gives $C=0.007\times0.393=0.003$. Both factors approach but
never reach $1$, because there is always residual uncertainty about a behavioural inference and a
system that reports absolute certainty is lying.

$C$ is a **heuristic confidence index**, not a posterior probability and not a coverage level.
It says how much to trust the measurement, while $\pi$ says how much the measurement favours
$H_1$; the two are distinct fields for exactly that reason. A high score with low confidence means
"startling, but we have barely looked".

---

## 8. Evidence-strength bands and the forensic scale

**Method:** `EvidenceStrength.fromLogOdds`.

Bands are defined on the **posterior log-odds** $P$ (equivalently decibans), not on the suspicion
percentage, because a probability scale compresses exactly the range where the distinctions live
($0.99$ and $0.999$ are adjacent in probability but a full order of magnitude apart in evidence),
and because a probability invites the reader to treat it as a frequency.

| Band | Condition (with $n\ge n_{\min}$, $G\ge G_{\min}$) | Nominal likelihood ratio |
| --- | --- | --- |
| `VERY_STRONG` | $P \ge \ln 1000$ | 1000 |
| `STRONG` | $P \ge \ln 100$ | 100 |
| `MODERATE` | $P \ge \ln 10$ | 10 |
| `WEAK` | $P \ge \ln 3$ | 3 |
| `INSUFFICIENT` | $P < \ln 3$, or sample/groups below minimum | — |

with `minimumSampleSize` $=10$ and `minimumIndependentGroups` $=2$; if either is not met the
verdict is `INSUFFICIENT` regardless of how large $P$ is.

**Forensic-scale justification.** The labels and thresholds follow the conventional
Jeffreys / ENFSI verbal scale used in forensic science: a likelihood ratio of 10 is "moderate",
100 "strong", 1000 "very strong". Adopting an established scale means a moderator's intuition
from other domains transfers, and the metric is unambiguously a strength of evidence rather than
a claimed frequency of cheaters.

**Two honest caveats.**

1. The thresholds are compared against $P=\rho+\Delta$, which **includes the prior** $\rho\approx-3.89$.
   The band is therefore reached when $\Delta \ge \ln(\text{bound}) - \rho$: e.g. `WEAK` requires
   $\Delta \ge \ln 3 + 3.8918 = 4.990$, not $1.099$. The bands are *labelled* by likelihood ratios
   but are, strictly, thresholds on posterior odds. This is a deliberate simplification, not a
   normalisation error.
2. A Bayes factor is not a $p$-value. "Likelihood ratio 1000" does **not** mean "one false positive
   in 1000"; converting it to a false-positive rate requires a prior and a loss function, and this
   code does not claim one. The `indicativeProbability()` display method ($3\to0.75$, $10\to0.909$,
   $100\to0.990$, $1000\to0.999$) is the logistic of the *bound alone*, ignores the prior, and is
   used for human-facing reports only — never for decisions.

A player whose behaviour argues *against* $H_1$ produces strongly negative $P$, which falls into
the same `INSUFFICIENT` branch. This is deliberate: labelling exculpatory evidence "weak
suspicion" would read as a mild accusation in a moderation report.

---

## 9. Geometry

Geometry never decides a verdict. It exists to *modulate* other evidence (a suspicious discovery
rate achieved along a suspiciously efficient path is more informative than the same rate achieved
while wandering) and to help a moderator interpret the numbers.

### 9.1 The solid-angle baseline for look-alignment

**Source:** `MapOreProfileRegistry` (0.067 baseline), `OreTargetingComponent`.

The probability that an aimless heading lands inside a cone of half-angle $\theta$ is the fraction
of the sphere's solid angle the cone subtends. The solid angle of a cone is
$$\Omega(\theta) = \int_0^{2\pi}\!\!\int_0^{\theta}\sin\theta'\,d\theta'\,d\varphi
= 2\pi\big(1-\cos\theta\big).$$
The full sphere is $4\pi$, so
$$\boxed{\;P_{\text{cone}}(\theta) = \frac{2\pi(1-\cos\theta)}{4\pi} = \frac{1-\cos\theta}{2}\;}$$

For $\theta = 30^\circ$:
$$P_{\text{cone}}(30^\circ) = \frac{1-\cos 30^\circ}{2}
= \frac{1-0.8660254}{2} = 0.0669873 \approx 0.067 .$$

This is the exact geometric value, not a tuned constant, and it is why the model is defensible
from first principles. It is used as `legitimateLookAlignmentProbability`; the ore-informed
counterpart is $0.5$, giving a per-aligned-look likelihood ratio of $0.5/0.067 = 7.46$ and a
per-non-aligned-look ratio of $0.5/0.933 = 0.536$. Movement alignment is given a deliberately
weaker model, so that a strip-miner — who mines whatever lies ahead and therefore almost by
construction finds ore in front of them — is not flagged: the **shipped** probabilities are
$p_0=0.5$, $p_1=0.8$ (`OreProfile.Builder` defaults, not overridden by
`MapOreProfileRegistry.defaults()`), so an aligned movement direction is worth only
$\ln(0.8/0.5)=\ln 1.6$. (The `OreTargetingComponent` javadoc quotes $0.45$ and $0.75$; those
values are not what the shipped profiles use. See the limitations section.)

Measurement detail: the approach heading is sampled at the last path point at least
`lookbackDistanceBlocks` $=8$ blocks from the ore, not at arrival — at arrival every player is
adjacent to the ore and the angle is meaningless. The angle is computed as
$\operatorname{atan2}(|\mathbf{u}\times\mathbf{v}|,\ \mathbf{u}\cdot\mathbf{v})$, which is
numerically stable for near-parallel vectors where $\arccos$ is ill-conditioned.

### 9.2 PCA / eigendecomposition of the covariance matrix

**Method:** `PrincipalAxes.of`, `jacobiEigen`, `TrajectoryAnalysis.geometry`.

A mined tunnel or a vein is described as a point cloud of block centres. With
$\boldsymbol{\mu}=\frac1N\sum_j \mathbf{p}_j$, the covariance matrix (biased/MLE form, dividing by
$N$) is the symmetric $3\times3$
$$\mathbf{C} = \frac1N\sum_{j=1}^{N}(\mathbf{p}_j-\boldsymbol{\mu})(\mathbf{p}_j-\boldsymbol{\mu})^{\!\top}
= \begin{pmatrix} c_{xx} & c_{xy} & c_{xz}\\ c_{xy} & c_{yy} & c_{yz}\\ c_{xz} & c_{yz} & c_{zz}\end{pmatrix}.$$
Its eigendecomposition $\mathbf{C}=\mathbf{V}\boldsymbol{\Lambda}\mathbf{V}^{\!\top}$ gives
orthonormal axes $\mathbf{v}_1,\mathbf{v}_2,\mathbf{v}_3$ and eigenvalues
$\ell_1\ge\ell_2\ge\ell_3$ (sorted descending, so $\mathbf{v}_1$ is always the dominant
direction). The derived features are:

$$\text{straightness} = \frac{\ell_1}{\ell_1+\ell_2+\ell_3},\qquad
\text{linearity} = \frac{\ell_1-\ell_2}{\ell_1},\qquad
\text{planarity} = \frac{\ell_2-\ell_3}{\ell_1},\qquad
\text{rms off-axis deviation} = \sqrt{\ell_2+\ell_3}.$$

Straightness near $1$ means the cloud is essentially one-dimensional (a straight tunnel); near
$1/3$ means it is isotropic. Linearity is the standard dimensionality descriptor, near $1$ for a
clean line; planarity is high for a flat, gallery-like or wall-like cloud.

**Why Jacobi rotation.** The decomposition uses the classical cyclic Jacobi method rather than a
general-purpose linear algebra library. For a fixed $3\times3$ symmetric matrix Jacobi converges
quadratically and terminates in a handful of sweeps; it needs no pivoting heuristic and no external
dependency, and it is numerically robust because it applies only orthogonal similarity transforms,
which preserve symmetry and cannot amplify error. At each step the rotation for the off-diagonal
entry $(p,q)$ is chosen to zero it:
$$\tau = \frac{a_{qq}-a_{pp}}{2a_{pq}},\qquad
t = \frac{\operatorname{sgn}\tau}{|\tau|+\sqrt{\tau^2+1}},\qquad
c=\frac{1}{\sqrt{t^2+1}},\qquad s=tc,$$
taking the smaller-magnitude root to avoid cancellation when $\tau$ is large. Sweeps continue
until the off-diagonal magnitude is below $10^{-12}$ or 50 sweeps have run. Eigenvectors are
defined only up to sign; where the sign matters (a direction of travel) the caller re-derives it by
projection. Because only eigenvalues enter the features as *ratios*, the biased/MLE choice of
dividing by $N$ does not affect any decision.

### 9.3 Path efficiency, turning angle, and the capped contribution

**Method:** `TrajectoryAnalysis.geometry`, `TunnelGeometryComponent.evaluate`.

- **Path efficiency** is net displacement over path length, exactly
  $$\text{efficiency} = \mathrm{clamp}\!\left(\frac{\|\mathbf{p}_{N}-\mathbf{p}_{1}\|}{\text{path length}},0,1\right),$$
  where path length is $\sum_i\|\mathbf{p}_i-\mathbf{p}_{i-1}\|$. Near $1$ means the player
  travelled in a near-straight line; near $0$ means they wandered or backtracked.
- **Turning angle** between successive segments is $\operatorname{atan2}(|\mathbf{u}\times\mathbf{v}|,\mathbf{u}\cdot\mathbf{v})$
  in degrees. A heading change of $\ge 30^\circ$ (`TURN_THRESHOLD_DEGREES`) counts as a "turn";
  `turnDensity` is turns per 100 blocks, a scale-free measure of how much direction changes.
- **Composite linearity** as used by the geometry component is the average of two orthogonal
  notions of directness,
  $$L = 0.5\cdot\text{straightness} + 0.5\cdot\text{path efficiency}.$$
  A path can be straight without being efficient (walk straight, pause, walk straight again) and
  efficient without being globally straight.

The geometry contribution is
$$\boxed{\;\mathrm{LLR}_{\text{geo}} = \mathrm{clamp}\!\big(1.2\,(L - 0.75),\;-\ln 2,\;+\ln 2\big)\;}$$
with `NEUTRAL_LINEARITY` $=0.75$. It adds **at most a likelihood ratio of 2 in either
direction** — a small nudge that can never carry a verdict — and declares reliability $0.4$, so its
maximum effective contribution to the sum is $\ln 2\times0.4 = 0.277$ (an effective ratio of
$2^{0.4}\approx1.32$). Its sample size is fixed at $1$ per window: the path is one observation, and
treating its hundreds of vertices as independent samples would let this weak signal dominate the
engine's sample-size accounting and inflate confidence in everything else. Tunnel geometry is the
classic false-positive generator — strip-mining and ore-seeking both produce long straight tunnels,
branch-mining and systematic cheating both produce grids — so no shape is treated as decisive.

---

## 10. Limitations and known approximations

Stated plainly, in the order they matter.

1. **All rate and probability priors are beliefs, not measurements.** The per-ore
   `hiddenDiscoveryRatePerThousandBlocks` values (diamond $1.5$, emerald $0.35$, ancient debris
   $2.5$ per 1000 blocks) and the multipliers ($6,6,4$) are conservative prior choices documented
   in `MapOreProfileRegistry`, not empirical fits to a server's logs. The design tolerates a
   prior wrong by a factor of two — it shifts the accumulated evidence slightly rather than
   flipping a verdict — but the *order of magnitude* and the *relative ordering* between ores must
   be roughly right, and on a modded or amplified world they may not be. They are configurable for
   this reason.
2. **The alignment models are geometric for look, but chosen for movement.** The $0.067$ look
   baseline is exact geometry (§9.1). The movement probabilities and the informed-look
   probability $0.5$ are modelling choices, not derivations, and the informed values are
   unfalsifiable without labelled cheating data that the project does not have.
3. **Movement-alignment defaults are $0.5$ / $0.8$, and the code now says so.** The shipped profiles
   never call `moveAlignmentProbabilities(...)`, so `OreProfile.Builder`'s defaults of $0.5$
   (legitimate) and $0.8$ (informed) are what actually run. `OreTargetingComponent`'s javadoc
   previously quoted $0.45$ / $0.75$, which no configuration produced; it has been corrected to match
   the defaults, so code and documentation now agree.
4. **Uncertainty is excluded, not credited.** The `ExposureState.hiddennessWeight()` table gives
   `UNKNOWN` a neutral $0.5$, sitting between `PARTIALLY_EXPOSED` ($0.25$) and `HIDDEN` ($1.0$). The
   exposure-mix component implements the corresponding rule directly rather than via that method: it
   counts a discovery as *hidden* only when `isUnaccountablyHidden()` holds, and as *exposed* only
   when `isVisibleByOrdinaryPlay()` holds. A discovery whose state is `UNKNOWN` — or
   `CONDITIONALLY_EXPOSED`, meaning ore reached through excavation by anybody — is therefore excluded
   from the mix entirely rather than being counted as visible. The denominator is the classified
   count, so missing world history leaves the observed hidden fraction unchanged instead of biasing it
   downward. `EvidenceEngineTest.unknownIsExcludedFromTheMix` asserts this, and
   `otherPlayersTunnelIsNotExculpatory` asserts the same exclusion for `CONDITIONALLY_EXPOSED` ore.
   (`hiddennessWeight()` itself is no longer called from any component; the two predicates above are
   the mechanism, and the weight table documents the ordering they encode.)
5. **Per-ore LLRs are averaged, not jointly modelled.** The discovery-rate and targeting
   components compute a per-ore LLR, weight it by `evidenceWeight` (default $1.0$), and divide by
   the weight sum. This is a weighted mean of separate two-hypothesis tests, not one likelihood
   ratio over a joint distribution across ores. If two ores are enabled and disagree, the average
   can be unrepresentative of either.
6. **The waiting component uses one ore's rates for all intervals.** To avoid an exponential
   mixture it selects the player's dominant hidden ore and applies that ore's rates to every
   interval, including intervals that ended at a different ore. Where multiple ores are found in
   one window this is a genuine model mismatch, flagged in the component's own comment.
7. **The rate and waiting components are algebraically near-redundant.** When the intervals
   partition the exposure, the exponential LLR equals the Poisson LLR (§3.3). They are grouped so
   they are not double-counted, but this also means the "second view" adds little *new*
   information in the simple case; its value is in detecting pause/regularity patterns that the
   count alone misses.
8. **Shrinkage is applied once, using the maximum group sample.** A large sample in one family
   partially unshrinks every other family's evidence (§6). This is a simplification.
9. **Confidence $C$ is a heuristic index**, a product of saturating exponentials with hand-chosen
   scales. It is not a posterior probability, a coverage level, or a calibration guarantee.
10. **Bands are on posterior odds, not likelihood ratios** (§8). The nominal ratio labels are
    shifted by the prior $\rho$, and a Bayes factor is not a $p$-value.
11. **Numerical guards that are not mathematics.** `LikelihoodRatios.safeLogRatio` floors the
    numerator and denominator at $1\times10^{-12}$ for the binomial ratio; the profile constructor
    forbids boundary probabilities, so this only ever degrades a misconfigured boundary to "very
    strong evidence" instead of $-\\infty$ poisoning the sum. The Poisson tail p-value
    (`PoissonDistribution.upperTailPValue`) falls back to a continuity-corrected normal
    approximation when the modal term underflows below $10^{-700}$; it is reported for human
    explanation and is **not** used for decisions.
12. **Clamping and saturation.** $\Delta$ sums are ultimately clamped at $\pm30$. A genuinely
    decisive case reports the clamp, so the reported posterior is not an unbounded accumulation —
    the bands top out at `VERY_STRONG` and no finer ranking exists above it.
13. **The exposure classifier is an approximation.** `ExposureAnalyzer` walks sample points
    between block centres rather than performing Minecraft's own raycast, and ignores per-block
    models and partial-transparency shapes. It is used only to *downgrade* a `HIDDEN` verdict to
    `PARTIALLY_EXPOSED`, never the reverse, so the approximation can only make the system more
    lenient toward the player.

---

## 11. False-positive risk

The engineering objective is asymmetric: a false accusation is far more costly than a missed
detector, so every ambiguity is resolved toward "not enough evidence".

Mechanisms that reduce false positives:

- A **low prior** ($\rho=\ln(0.02/0.98)$) means the evidence itself must move the verdict; a
  default suspicion is not doing the work.
- **Exact geometric baselines** where a baseline is claimed to be principled (§9.1), and a
  deliberately weak movement model that does not flag strip-miners.
- **Shrinkage** ($s(n)=n/(n+\kappa)$) so small samples cannot move the verdict.
- **Two independent gates** before enforcement: evidence strength *and* statistical confidence.
  A very strong ratio from four observations cannot ban anyone.
- **Minimum sample size and minimum independent groups** force breadth before any conclusion is
  allowed, implementing the anti-evasion principle that one signal, however strong, is not enough.
- **The exposure-mix component returns negative evidence for cave explorers**, so a
  superficially alarming discovery count that is explained by legitimate exploration reduces
  suspicion rather than being ignored.
- **Geometry is capped** at a likelihood ratio of 2 and cannot carry a verdict.
- **Right-censoring** of the trailing interval removes a one-sided bias toward suspicion.

Residual risk that these mechanisms do **not** eliminate:

- A legitimately lucky player in an unusually rich region can beat the rate prior substantially;
  the crossover of §3.1 means they must exceed roughly $2.8\times$ the prior rate *before any
  positive evidence accrues*, and shrinkage then discounts what does accrue, but a sustained run of
  luck plus one moderately suspicious timing signal could reach the `WEAK` band.
- A player mining a very large deepslate cave system finds a lot of ore with little rock moved;
  their rate signal is controlled by the exposure penalty term, but the mix component is doing
  real work here and depends on the configured `legitimateHiddenFraction`, which no server knows
  precisely.
- Because the band thresholds are on posterior odds and are labelled by likelihood ratios (§8),
  the intuitive "1000:1" reading overstates the false-positive control compared with what an
  explicit error-rate analysis would show. No empirical false-positive rate is claimed anywhere in
  the code, and none can be, without labelled data.
- Origin attribution and the `UNKNOWN` state (§10.4) are not applied uniformly, so worlds with
  aggressive chunk pruning or history truncation do not behave exactly as the design intends.

The honest summary: the model is built to make a false accusation *hard*, not impossible, and it
reports confidence and sample size precisely so that a human can see when a strong-looking ratio
rests on thin data. Full worked numbers are in `STATISTICAL_MODEL.md`.
