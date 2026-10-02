package io.xrayac.core.evidence;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.statistics.LogOdds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Combines independent evidence components into a single, explainable suspicion assessment.
 *
 * <h2>Why log-odds accumulation</h2>
 * Components each return a log-likelihood ratio. Because independent evidence combines by
 * Bayesian updating, and in log-odds space that is addition, the engine's core operation is a sum.
 * This is not a cosmetic choice: a weighted sum of log-ratios cannot drift outside a representable
 * range, is order-independent, and — crucially — makes every component's individual influence
 * inspectable after the fact, which is what allows the plugin to answer "why?" rather than only
 * "how much?".
 *
 * <h2>What the engine does to the raw sum, and why each step exists</h2>
 * <ol>
 *   <li><b>Sample-size shrinkage</b>, {@code n/(n+k)}. A component's nominal ratio is what it would
 *       mean if the model were exactly right and the sample large. With a handful of observations
 *       it should not be taken at face value, so it is scaled toward zero. This is the single
 *       mechanism that prevents "three lucky finds" from producing a dramatic score.</li>
 *   <li><b>Time decay</b>, {@code exp(-lambda t)}. Observations age; behaviour changes. The decay
 *       factor is the mean weight of the window's discoveries, so a window full of stale
 *       observations contributes proportionally less, and re-evaluating a stored window months
 *       later reproduces the diminished value.</li>
 *   <li><b>Independence-aware sample counting</b>. Components that measure the same underlying
 *       signal declare a shared group; the engine sums the <i>maximum</i> sample size within each
 *       group rather than the total, so four views of one discovery rate count as one signal's
 *       worth of data.</li>
 * </ol>
 *
 * <h2>Failure isolation</h2>
 * A component that throws is recorded as a non-informative contribution with the failure quoted in
 * its explanation, and the remaining components are still evaluated. One broken signal must never
 * deny a player an assessment — and an invisible failure would be worse, because the absent signal
 * would silently look like the absence of evidence.
 *
 * <p>Immutable and thread-safe; a single instance is shared by all analysis workers.
 */
public final class EvidenceEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceEngine.class);

    private final List<EvidenceComponent> components;

    public EvidenceEngine(List<EvidenceComponent> components) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("at least one evidence component is required");
        }
        this.components = List.copyOf(components);
    }

    public List<EvidenceComponent> components() {
        return components;
    }

    /**
     * Evaluates a window and returns the assessment.
     *
     * @param evaluatedAt the instant "now" for the purpose of time decay. Passed explicitly so that
     *                    replaying a stored window reproduces the historical verdict rather than
     *                    silently re-decaying it against the current clock.
     */
    public SuspicionSnapshot evaluate(PlayerAnalysisWindow window,
                                      OreProfileRegistry profiles,
                                      EvidenceParameters parameters,
                                      Instant evaluatedAt) {

        List<EvidenceContribution> contributions = new ArrayList<>(components.size());
        for (EvidenceComponent component : components) {
            contributions.add(evaluateSafely(component, window, profiles, parameters));
        }

        // Count distinct signal families, and for each family take the largest sample it offers.
        Map<String, Integer> maxSampleByGroup = new HashMap<>();
        Map<String, Double> summedByGroup = new HashMap<>();
        for (EvidenceContribution contribution : contributions) {
            if (!contribution.isInformative()) {
                continue;
            }
            maxSampleByGroup.merge(contribution.independentGroup(),
                    contribution.sampleSize(), Math::max);
            summedByGroup.merge(contribution.independentGroup(),
                    contribution.weightedLogLikelihoodRatio(), Double::sum);
        }

        // The sample size is the largest single family's observation count, NOT the sum across
        // families. Every family here is fed by the same underlying events — the same handful of
        // ore discoveries is counted once by the rate component, once by the timing component and
        // once by the exposure-mix component. Adding those together would triple-count the evidence
        // base and let a player with six discoveries clear a ten-observation threshold. Taking the
        // maximum states the honest number: this is how many independent observations we actually
        // have about this player's behaviour.
        int sampleSize = maxSampleByGroup.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int independentGroups = maxSampleByGroup.size();

        double rawLogOdds = summedByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
        double shrink = parameters.shrinkFactor(sampleSize);
        double decay = meanDecay(window, parameters, evaluatedAt);
        double effectiveLogOdds = rawLogOdds * shrink * decay;

        double posteriorLogOdds = LogOdds.accumulate(parameters.priorLogOdds(), effectiveLogOdds);
        double suspicionScore = LogOdds.toProbability(posteriorLogOdds);
        double confidence = parameters.statisticalConfidence(sampleSize, independentGroups);
        EvidenceStrength strength = EvidenceStrength.fromLogOdds(
                posteriorLogOdds, sampleSize, independentGroups,
                parameters.minimumSampleSize(), parameters.minimumIndependentGroups());

        return new SuspicionSnapshot(
                window.player(), window.world(), evaluatedAt,
                parameters.priorLogOdds(), effectiveLogOdds, posteriorLogOdds,
                suspicionScore, confidence, decay, sampleSize, independentGroups,
                strength, contributions);
    }

    private EvidenceContribution evaluateSafely(EvidenceComponent component,
                                                PlayerAnalysisWindow window,
                                                OreProfileRegistry profiles,
                                                EvidenceParameters parameters) {
        try {
            EvidenceContribution contribution = component.evaluate(window, profiles, parameters);
            return contribution == null
                    ? EvidenceContribution.none(component.id(), component.independentGroup(),
                            "component returned no result")
                    : contribution;
        } catch (RuntimeException e) {
            // Isolated deliberately: one broken signal must not deny the player an assessment.
            LOGGER.error("Evidence component '{}' failed for player {}; continuing with remaining signals",
                    component.id(), window.player().id(), e);
            return EvidenceContribution.none(component.id(), component.independentGroup(),
                    "component '" + component.id() + "' failed and contributed no evidence: "
                            + e.getClass().getSimpleName());
        }
    }

    /**
     * Mean time-decay weight across the window's discoveries.
     *
     * <p>An empty window has no evidence to age, so the neutral value 1 is returned.
     */
    private double meanDecay(PlayerAnalysisWindow window, EvidenceParameters parameters, Instant evaluatedAt) {
        List<OreDiscovery> discoveries = window.discoveries();
        if (discoveries.isEmpty()) {
            return 1.0;
        }
        double total = 0.0;
        for (OreDiscovery discovery : discoveries) {
            double ageHours = Duration.between(discovery.time(), evaluatedAt).toMillis() / 3_600_000.0;
            total += parameters.decayWeight(ageHours);
        }
        return total / discoveries.size();
    }
}
