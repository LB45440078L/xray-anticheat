package io.xrayac.core.statistics;

/**
 * Poisson distribution over event counts, parameterised by its mean {@code lambda}.
 *
 * <p>Used to model <b>counts of independent rare events in a fixed amount of exposure</b>:
 * the number of hidden ore blocks a player discovers per thousand blocks mined is the
 * canonical example. The Poisson process assumption is defensible because ore occurrences
 * are (to a good approximation) independent rare events distributed uniformly through mined
 * volume, and because the quantity we compare is a rate per unit exposure rather than a
 * count per unit time — the exposure variable is "blocks mined", not wall-clock seconds,
 * which removes the confound of players who simply play longer or mine faster.
 *
 * <p>Valid for any {@code lambda > 0}. For very large {@code lambda} the exact tail sum
 * underflows; the tail routine then falls back to a normal approximation with a continuity
 * correction, and this substitution is documented rather than hidden.
 */
public record PoissonDistribution(double lambda) implements Distribution {

    public PoissonDistribution {
        if (!(lambda > 0.0) || !Double.isFinite(lambda)) {
            throw new IllegalArgumentException("Poisson lambda must be finite and > 0, got " + lambda);
        }
    }

    @Override
    public double logDensity(double x) {
        // Discrete support: only non-negative integers carry mass.
        if (x < 0 || Math.abs(x - Math.rint(x)) > 1e-9) {
            return Double.NEGATIVE_INFINITY;
        }
        int k = (int) Math.rint(x);
        return k * Math.log(lambda) - lambda - SpecialFunctions.logFactorial(k);
    }

    /** Probability mass at the integer {@code k}. */
    public double pmf(int k) {
        return Math.exp(logDensity(k));
    }

    /**
     * Upper-tail p-value {@code P(X >= observed)}.
     *
     * <p>This is the raw, one-sided "how surprising is this count" probability. The engine
     * does <b>not</b> use it directly for decisions — it feeds likelihood ratios instead —
     * but it is reported in evidence explanations because it is the quantity an
     * administrator intuitively reasons with.
     */
    public double upperTailPValue(int observed) {
        if (observed <= 0) {
            return 1.0;
        }
        double logPk = logDensity(observed);
        if (logPk < -700.0) {
            // The modal term underflows; the exact forward recurrence would be all zeros.
            return normalApproximationTail(observed);
        }
        double term = Math.exp(logPk);
        double sum = term;
        for (int i = observed + 1; i <= observed + 100_000; i++) {
            term *= lambda / i;
            sum += term;
            if (term <= sum * 1e-16) {
                break;
            }
        }
        return Math.min(1.0, sum);
    }

    private double normalApproximationTail(int observed) {
        // Continuity-corrected normal approximation: P(X >= k) ~ P(Z >= (k - 0.5 - lambda)/sqrt(lambda))
        double z = (observed - 0.5 - lambda) / Math.sqrt(lambda);
        return 1.0 - NormalDistribution.standardCdf(z);
    }

    @Override
    public double mean() {
        return lambda;
    }

    @Override
    public double variance() {
        return lambda;
    }

    @Override
    public String describe() {
        return String.format("Poisson(lambda=%.4f)", lambda);
    }
}
