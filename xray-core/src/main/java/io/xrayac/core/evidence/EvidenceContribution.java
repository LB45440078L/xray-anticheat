package io.xrayac.core.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The output of one evidence component: a signed log-likelihood ratio plus the metadata needed to
 * weight it honestly and to explain it to a human.
 *
 * <p>The split between {@code logLikelihoodRatio} and {@code reliability} matters. The LLR is a
 * statement about the data ("these counts are 40 times more likely under the ore-informed
 * hypothesis"). Reliability is a statement about the model ("and I trust this particular model
 * to be calibrated for this kind of observation"). Keeping them separate means a component whose
 * data is suggestive but whose model is shaky declares both facts, instead of silently producing
 * a large number nobody should act on.
 *
 * @param componentId        stable identifier of the producing component
 * @param independentGroup   components sharing a group are measuring the same underlying signal,
 *                           and only one of them may count toward the "independent signals" tally.
 *                           Without this, four restatements of the same discovery rate would look
 *                           like four independent confirmations.
 * @param logLikelihoodRatio signed raw evidence; positive favours the ore-informed hypothesis
 * @param sampleSize         number of independent observations behind the ratio
 * @param reliability        intrinsic trust in the model for this data, in {@code [0, 1]}
 * @param explanation        a sentence a moderator can read and check
 * @param metrics            named quantities quoted in the explanation, for the GUI and the
 *                           Python visualiser
 */
public record EvidenceContribution(
        String componentId,
        String independentGroup,
        double logLikelihoodRatio,
        int sampleSize,
        double reliability,
        String explanation,
        Map<String, Double> metrics) {

    public EvidenceContribution {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId is required");
        }
        if (independentGroup == null || independentGroup.isBlank()) {
            throw new IllegalArgumentException("independentGroup is required");
        }
        if (!Double.isFinite(logLikelihoodRatio)) {
            // A non-finite ratio would poison the sum. Components must clamp their own output.
            throw new IllegalArgumentException("logLikelihoodRatio must be finite, got " + logLikelihoodRatio);
        }
        if (sampleSize < 0) {
            throw new IllegalArgumentException("sampleSize cannot be negative");
        }
        if (!(reliability >= 0.0) || !(reliability <= 1.0)) {
            throw new IllegalArgumentException("reliability must lie in [0, 1], got " + reliability);
        }
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("every contribution must be explainable");
        }
        metrics = Map.copyOf(metrics);
    }

    /** A contribution that carries no evidence, with the reason recorded. */
    public static EvidenceContribution none(String componentId, String group, String reason) {
        return new EvidenceContribution(componentId, group, 0.0, 0, 0.0, reason, Map.of());
    }

    /**
     * The contribution actually added to the running total: the raw ratio, scaled by how much the
     * model should be trusted for this data. Sample-size shrinkage and time decay are applied by
     * the engine, not here, so that a component's numbers stay interpretable on their own.
     */
    public double weightedLogLikelihoodRatio() {
        return logLikelihoodRatio * reliability;
    }

    public boolean isInformative() {
        return sampleSize > 0 && Math.abs(logLikelihoodRatio) > 1e-9 && reliability > 0.0;
    }

    /** Convenience builder for metrics maps. */
    public static Map<String, Double> metrics(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("metrics require key/value pairs");
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(String.valueOf(keyValuePairs[i]), ((Number) keyValuePairs[i + 1]).doubleValue());
        }
        return map;
    }
}
