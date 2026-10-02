package io.xrayac.core.statistics;

/**
 * Log-odds arithmetic and its conversions.
 *
 * <p>The entire evidence engine accumulates belief in <b>log-odds (natural log) space</b>.
 * The reason is structural rather than cosmetic: independent pieces of evidence combine by
 * Bayesian updating, which in probability space is a product and in log-odds space is a
 * plain sum. Summation is associative and commutative, cannot drift outside a representable
 * range the way a running product of probabilities can (underflow to exactly zero destroys
 * the ability to recover), and makes evidence decay a simple weighted sum. Log-odds is
 * therefore the natural currency of the accumulator.
 *
 * <p>Terminology used consistently throughout the codebase:
 * <ul>
 *   <li>{@code priorLogOdds} — belief before observing this player's behaviour;</li>
 *   <li>{@code logLikelihoodRatio} (LLR) — how much one observation shifts belief;</li>
 *   <li>{@code posteriorLogOdds} — the accumulated result.</li>
 * </ul>
 */
public final class LogOdds {

    /**
     * Log-odds are clamped to this magnitude (roughly 1e-13 probability either way) before
     * exponentiation.
     *
     * <p>The clamp exists so that a long run of evidence cannot overflow {@code exp} and
     * produce an infinite score, and so that a single overwhelming observation cannot pin
     * the posterior at exactly 1.0 and thereby freeze the model against later
     * contradicting evidence. The bound is far beyond any decision threshold, so it never
     * changes a decision — it only keeps the arithmetic total.
     */
    public static final double MAX_MAGNITUDE = 30.0;

    private LogOdds() {
    }

    /** Converts a probability in {@code (0, 1)} to natural log-odds. */
    public static double fromProbability(double probability) {
        if (probability <= 0.0) {
            return -MAX_MAGNITUDE;
        }
        if (probability >= 1.0) {
            return MAX_MAGNITUDE;
        }
        return Math.log(probability / (1.0 - probability));
    }

    /**
     * Converts log-odds to a probability, clamping to a numerically safe range.
     *
     * <p>Uses the numerically stable branch decisions rather than the naive
     * {@code 1/(1+exp(-x))} form: for large negative {@code x}, {@code exp(-x)} overflows,
     * and for large positive {@code x} the naive subtraction {@code 1 - p} loses precision.
     */
    public static double toProbability(double logOdds) {
        double clamped = clamp(logOdds);
        if (clamped >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-clamped));
        }
        double e = Math.exp(clamped);
        return e / (1.0 + e);
    }

    public static double clamp(double logOdds) {
        return Math.clamp(logOdds, -MAX_MAGNITUDE, MAX_MAGNITUDE);
    }

    /**
     * Adds a log-likelihood ratio to a running log-odds total, clamping the result.
     */
    public static double accumulate(double runningLogOdds, double logLikelihoodRatio) {
        return clamp(runningLogOdds + logLikelihoodRatio);
    }

    /**
     * Expresses log-odds in decibans (10 log10), a unit from forensic statistics that is
     * convenient when quoting evidence strength to a human moderator, since the familiar
     * landmarks fall on whole numbers (10 dB is "moderate", 20 dB is "strong").
     */
    public static double toDecibans(double logOdds) {
        return logOdds / Math.log(10.0) * 10.0;
    }
}
