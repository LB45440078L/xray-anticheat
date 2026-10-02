package io.xrayac.core.statistics;

import java.util.List;

/**
 * Closed-form log-likelihood ratios for the models the engine uses.
 *
 * <p>Each method returns
 * {@code ln [ P(observation | H1) / P(observation | H0) ]},
 * where <b>H0 is legitimate mining</b> and <b>H1 is ore-informed (X-ray) mining</b>.
 *
 * <p>A positive result is evidence for H1, a negative result is evidence for H0, and zero
 * is uninformative. Returning log ratios rather than raw probabilities is what allows the
 * accumulator to behave correctly when many weak observations combine: a pile of individually
 * unimpressive observations can accumulate to a decisive total, and — equally important — a
 * single surprising observation cannot, because its contribution is bounded by how well the
 * models actually separate the two hypotheses.
 */
public final class LikelihoodRatios {

    private LikelihoodRatios() {
    }

    /**
     * Log-likelihood ratio for a Poisson count with known exposure.
     *
     * <p>Derivation. Under {@code H0} the count is {@code Poisson(lambda0 * exposure)} and
     * under {@code H1} it is {@code Poisson(lambda1 * exposure)}. The log mass functions are
     * {@code k ln(lambda E) - lambda E - ln(k!)}; the {@code ln(k!)} terms cancel because the
     * two hypotheses share the same observation space, leaving
     *
     * <pre>
     *   LLR = k * ln(lambda1 / lambda0) - (lambda1 - lambda0) * exposure
     * </pre>
     *
     * <p>Note the second term: it penalises H1 when exposure is large but the count is not
     * correspondingly large. This is what stops the engine from treating "mined a great deal"
     * as suspicious, and it is why exposure (blocks mined) must be passed honestly.
     *
     * @param observed          the observed number of hidden-ore discoveries
     * @param exposure          the exposure in the model's units (normally blocks mined)
     * @param lambda0PerExposure expected rate under legitimate mining
     * @param lambda1PerExposure expected rate under ore-informed mining
     */
    public static double poissonCount(int observed, double exposure,
                                      double lambda0PerExposure, double lambda1PerExposure) {
        requirePositive(lambda0PerExposure, "lambda0");
        requirePositive(lambda1PerExposure, "lambda1");
        if (observed < 0) {
            throw new IllegalArgumentException("observed count must be >= 0");
        }
        if (exposure < 0.0 || !Double.isFinite(exposure)) {
            throw new IllegalArgumentException("exposure must be finite and >= 0");
        }
        if (exposure == 0.0) {
            // The player produced no exposure at all, so the count carries no rate
            // information in either direction.
            return 0.0;
        }
        double ratio = Math.log(lambda1PerExposure / lambda0PerExposure);
        double penalty = (lambda1PerExposure - lambda0PerExposure) * exposure;
        return observed * ratio - penalty;
    }

    /**
     * Log-likelihood ratio for a binomial count of successes in {@code trials} independent
     * opportunities.
     *
     * <p>Derivation. With success probabilities {@code p0} (H0) and {@code p1} (H1) and
     * {@code k} successes in {@code n} trials, the binomial mass functions share the
     * coefficient {@code C(n, k)}, so
     *
     * <pre>
     *   LLR = k * ln(p1 / p0) + (n - k) * ln((1 - p1) / (1 - p0))
     * </pre>
     *
     * <p>Used for genuinely binary signals, such as "of the N hidden ores approached from a
     * pre-visibility direction, how many were approached within the aligned window". The
     * <b>independence</b> of the trials is an assumption the caller must uphold; the engine
     * enforces it by counting only one opportunity per discovered vein rather than one per
     * ore block, since the blocks of a single vein are strongly correlated.
     */
    public static double binomialCount(int successes, int trials, double p0, double p1) {
        if (trials < 0 || successes < 0 || successes > trials) {
            throw new IllegalArgumentException("require 0 <= successes <= trials, got "
                    + successes + " of " + trials);
        }
        requireUnitInterval(p0, "p0");
        requireUnitInterval(p1, "p1");
        if (trials == 0) {
            return 0.0;
        }
        double failures = trials - successes;
        double llr = 0.0;
        if (successes > 0) {
            llr += successes * safeLogRatio(p1, p0);
        }
        if (failures > 0) {
            llr += failures * safeLogRatio(1.0 - p1, 1.0 - p0);
        }
        return llr;
    }

    /**
     * Log-likelihood ratio for a set of exponential waiting times between events, with
     * support for right-censored observations.
     *
     * <p>For an <i>observed</i> interval {@code x} the log density is
     * {@code ln(rate) - rate * x}; for a <i>right-censored</i> interval the likelihood
     * contribution is the survival function {@code -rate * x}. Summing the H1 log terms and
     * subtracting the H0 log terms gives
     *
     * <pre>
     *   LLR = n_observed * ln(rate1 / rate0)
     *         - (rate1 - rate0) * (sum of observed x)
     *         - (rate1 - rate0) * (sum of censored x)
     * </pre>
     *
     * <p>which collapses to the same linear form as the Poisson case with the total
     * accumulated exposure. Censored intervals matter: a player who logs off mid-interval
     * contributes a partial observation that must not be discarded, because discarding long
     * intervals systematically shortens the observed mean and would bias the model toward
     * suspicion.
     */
    public static double exponentialIntervals(List<IntervalObservation> intervals,
                                              double rate0, double rate1) {
        requirePositive(rate0, "rate0");
        requirePositive(rate1, "rate1");
        int observed = 0;
        double total = 0.0;
        for (IntervalObservation interval : intervals) {
            if (interval.value() < 0.0 || !Double.isFinite(interval.value())) {
                throw new IllegalArgumentException("interval value must be finite and >= 0");
            }
            if (!interval.rightCensored()) {
                observed++;
            }
            total += interval.value();
        }
        if (intervals.isEmpty()) {
            return 0.0;
        }
        return observed * Math.log(rate1 / rate0) - (rate1 - rate0) * total;
    }

    /**
     * {@code ln(a / b)} with a defined result when {@code a} is exactly zero.
     *
     * <p>When the numerator probability is zero, H1 assigns no mass to the observation at
     * all. The mathematically correct LLR is {@code -infinity}, but returning that would
     * annihilate every other contribution and is almost always an artefact of a boundary
     * configuration parameter rather than genuine impossibility. Callers guard against
     * boundary parameters up front; this helper additionally floors the ratio so that a
     * misconfigured boundary degrades to "very strong evidence" instead of a poisoned sum.
     */
    private static double safeLogRatio(double a, double b) {
        final double floor = 1e-12;
        double numerator = Math.max(a, floor);
        double denominator = Math.max(b, floor);
        return Math.log(numerator / denominator);
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and > 0, got " + value);
        }
    }

    private static void requireUnitInterval(double value, String name) {
        if (!(value > 0.0) || !(value < 1.0)) {
            throw new IllegalArgumentException(name + " must lie strictly in (0, 1), got " + value);
        }
    }

    /**
     * One waiting-time observation, possibly right-censored.
     *
     * @param value         elapsed exposure (blocks, distance or seconds) since the previous event
     * @param rightCensored true when the interval is only known to be at least {@code value}
     *                      (the session or window ended before the next event)
     */
    public record IntervalObservation(double value, boolean rightCensored) {

        public static IntervalObservation observed(double value) {
            return new IntervalObservation(value, false);
        }

        public static IntervalObservation censored(double value) {
            return new IntervalObservation(value, true);
        }
    }
}
