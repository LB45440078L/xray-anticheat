package io.xrayac.core.evidence;

/**
 * Global parameters of the evidence engine.
 *
 * <p>Every value here has a documented meaning and a defensible default; none is a magic number
 * buried in an algorithm. They are separated from per-ore parameters because they describe how
 * evidence is <i>combined and decayed</i>, not what any particular ore looks like.
 *
 * @param priorLogOdds            belief before any behaviour is observed, as log-odds. The default
 *                                corresponds to a 2% prior probability that an arbitrary player is
 *                                using ore vision — deliberately low, because on a typical server
 *                                the overwhelming majority of players are honest, and a low prior
 *                                means real evidence must overcome it rather than a default
 *                                suspicion doing the work.
 * @param halfLifeHours           half-life of an observation's evidentiary weight. Behaviour
 *                                changes, accounts are sold, players reform; evidence that is a
 *                                week old should not weigh what it did on the day.
 * @param sampleSizeShrinkConstant {@code k} in the shrinkage factor {@code n/(n+k)} applied to the
 *                                summed evidence. With {@code k = 5}, a component backed by two
 *                                observations contributes only about 29% of its nominal weight;
 *                                one backed by fifty contributes 91%. This is the mechanism that
 *                                makes "three lucky finds" unable to move the verdict.
 * @param confidenceSampleScale   {@code n0} in the sample-adequacy term
 *                                {@code 1 - exp(-n/n0)}.
 * @param confidenceGroupScale    {@code g0} in the independence-adequacy term
 *                                {@code 1 - exp(-g/g0)}.
 * @param minimumSampleSize       smallest number of independent observations on which any
 *                                conclusion may be based. Below this the verdict is
 *                                {@link EvidenceStrength#INSUFFICIENT} no matter how extreme the
 *                                numbers look.
 * @param minimumIndependentGroups smallest number of independent signal families that must
 *                                contribute before a conclusion is allowed. This is the direct
 *                                implementation of the anti-evasion principle: one signal, however
 *                                strong, is not enough, because any single signal can be evaded.
 * @param lookbackDistanceBlocks  how far from an ore the approach heading is sampled, in blocks.
 *                                Far enough that the ore is still comfortably out of sight, close
 *                                enough that the player's heading is about this ore.
 */
public record EvidenceParameters(
        double priorLogOdds,
        double halfLifeHours,
        double sampleSizeShrinkConstant,
        double confidenceSampleScale,
        double confidenceGroupScale,
        int minimumSampleSize,
        int minimumIndependentGroups,
        double lookbackDistanceBlocks,
        double legitimateHiddenFraction,
        double oreInformedHiddenFraction) {

    public EvidenceParameters {
        if (!Double.isFinite(priorLogOdds)) {
            throw new IllegalArgumentException("priorLogOdds must be finite");
        }
        if (!(halfLifeHours > 0.0) || !Double.isFinite(halfLifeHours)) {
            throw new IllegalArgumentException("halfLifeHours must be finite and > 0");
        }
        if (sampleSizeShrinkConstant <= 0.0) {
            throw new IllegalArgumentException("sampleSizeShrinkConstant must be > 0");
        }
        if (confidenceSampleScale <= 0.0 || confidenceGroupScale <= 0.0) {
            throw new IllegalArgumentException("confidence scales must be > 0");
        }
        if (minimumSampleSize < 0) {
            throw new IllegalArgumentException("minimumSampleSize cannot be negative");
        }
        if (minimumIndependentGroups < 1) {
            throw new IllegalArgumentException("minimumIndependentGroups must be at least 1");
        }
        if (!(lookbackDistanceBlocks > 0.0)) {
            throw new IllegalArgumentException("lookbackDistanceBlocks must be > 0");
        }
        if (!(legitimateHiddenFraction > 0.0) || legitimateHiddenFraction >= 1.0) {
            throw new IllegalArgumentException("legitimateHiddenFraction must lie in (0, 1)");
        }
        if (!(oreInformedHiddenFraction > legitimateHiddenFraction) || oreInformedHiddenFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "oreInformedHiddenFraction must lie strictly between legitimateHiddenFraction and 1");
        }
    }

    public static EvidenceParameters defaults() {
        return new EvidenceParameters(
                /* priorLogOdds */ Math.log(0.02 / 0.98),
                /* halfLifeHours */ 168.0,
                /* sampleSizeShrinkConstant */ 5.0,
                /* confidenceSampleScale */ 20.0,
                /* confidenceGroupScale */ 2.0,
                /* minimumSampleSize */ 10,
                /* minimumIndependentGroups */ 2,
                /* lookbackDistanceBlocks */ 8.0,
                /* legitimateHiddenFraction */ 0.55,
                /* oreInformedHiddenFraction */ 0.95);
    }

    /** The exponential decay constant {@code lambda} implied by the configured half-life. */
    public double decayLambdaPerHour() {
        return Math.log(2.0) / halfLifeHours;
    }

    /**
     * The evidentiary weight of an observation {@code ageHours} old: {@code exp(-lambda * t)}.
     *
     * <p>The exponential form is chosen because it is the unique continuous decay that is
     * memoryless — the fraction of weight lost in the next hour does not depend on how old the
     * observation already is — and because it is the natural conjugate of the Poisson process that
     * generates the discoveries. A hard cut-off would create a cliff where a player's score drops
     * discontinuously an hour after an event, which is both surprising to administrators and easy
     * to game by pausing.
     */
    public double decayWeight(double ageHours) {
        if (ageHours <= 0.0) {
            return 1.0;
        }
        return Math.exp(-decayLambdaPerHour() * ageHours);
    }

    /** Sample-size shrinkage factor {@code n/(n+k)}, in {@code [0, 1)}. */
    public double shrinkFactor(int sampleSize) {
        if (sampleSize <= 0) {
            return 0.0;
        }
        return sampleSize / (sampleSize + sampleSizeShrinkConstant);
    }

    /**
     * Confidence in the measurement itself, combining how much data there is with how many
     * distinct kinds of signal contributed.
     *
     * <p>Both factors are computed as saturating exponentials, so confidence approaches but never
     * reaches 1: there is always residual uncertainty about a behavioural inference, and a system
     * that reports absolute certainty is lying. Multiplying the two factors means plenty of data
     * from a single signal family cannot substitute for independent corroboration.
     */
    public double statisticalConfidence(int sampleSize, int independentGroups) {
        double sampleAdequacy = 1.0 - Math.exp(-(double) sampleSize / confidenceSampleScale);
        double independenceAdequacy = 1.0 - Math.exp(-(double) independentGroups / confidenceGroupScale);
        return sampleAdequacy * independenceAdequacy;
    }
}
