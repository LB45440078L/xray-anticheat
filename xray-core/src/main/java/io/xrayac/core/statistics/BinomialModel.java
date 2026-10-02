package io.xrayac.core.statistics;

/**
 * Binomial inference helpers: exact p-values and two families of confidence interval.
 *
 * <p>Two intervals are provided deliberately, and the choice between them is a real one:
 * <ul>
 *   <li><b>Clopper-Pearson</b> is exact and conservative — its true coverage is never below
 *       the nominal level, so it never overstates certainty. This plugin uses it wherever the
 *       interval feeds a decision, because a conservative interval errs toward "not enough
 *       evidence", which is the correct direction of error when the consequence is accusing a
 *       player.</li>
 *   <li><b>Wilson score</b> has better average coverage for small samples and never collapses
 *       to a degenerate zero-width interval when a player has zero successes in a handful of
 *       trials. It is used for display, where stability across small samples matters more
 *       than guaranteed coverage.</li>
 * </ul>
 *
 * <p>The zero-success case is worth dwelling on: a naive {@code p-hat} interval reports
 * {@code [0, 0]} for "0 suspicious approaches in 5 opportunities", which reads as certainty
 * of innocence when the honest answer is "consistent with anything up to about 50%". The
 * Wilson interval gives {@code [0, 0.43]} there, which is the truthful statement.
 */
public final class BinomialModel {

    private BinomialModel() {
    }

    /**
     * Exact two-sided p-value for observing {@code successes} out of {@code trials} when the
     * true success probability is {@code p}.
     *
     * <p>Computed by summing the mass of every outcome whose probability is no greater than
     * the observed outcome's probability (the standard "method of small p-values"), with a
     * relative tolerance so that outcomes of essentially equal probability are not
     * arbitrarily excluded by floating-point noise.
     */
    public static double exactTwoSidedPValue(int successes, int trials, double p) {
        if (trials < 0 || successes < 0 || successes > trials) {
            throw new IllegalArgumentException("require 0 <= successes <= trials");
        }
        if (!(p >= 0.0) || !(p <= 1.0)) {
            throw new IllegalArgumentException("p must lie in [0, 1], got " + p);
        }
        if (trials == 0) {
            return 1.0;
        }
        if (p == 0.0) {
            return successes == 0 ? 1.0 : 0.0;
        }
        if (p == 1.0) {
            return successes == trials ? 1.0 : 0.0;
        }

        double observedMass = mass(successes, trials, p);
        double tolerance = 1.0 + 1e-7;
        double total = 0.0;
        for (int k = 0; k <= trials; k++) {
            double mass = mass(k, trials, p);
            if (mass <= observedMass * tolerance) {
                total += mass;
            }
        }
        return Math.min(1.0, total);
    }

    /** Binomial probability mass {@code P(X = k)}. */
    public static double mass(int k, int n, double p) {
        if (k < 0 || k > n) {
            return 0.0;
        }
        if (p == 0.0) {
            return k == 0 ? 1.0 : 0.0;
        }
        if (p == 1.0) {
            return k == n ? 1.0 : 0.0;
        }
        double logMass = SpecialFunctions.logBinomialCoefficient(n, k)
                + k * Math.log(p)
                + (n - k) * Math.log1p(-p);
        return Math.exp(logMass);
    }

    /**
     * Wilson score interval at the given confidence level.
     *
     * @param confidence confidence level in {@code (0, 1)}, e.g. {@code 0.95}
     * @return {@code [lower, upper]}
     */
    public static Interval wilsonInterval(int successes, int trials, double confidence) {
        if (trials <= 0) {
            return new Interval(0.0, 1.0);
        }
        double z = zForConfidence(confidence);
        double n = trials;
        double pHat = (double) successes / n;
        double z2OverN = z * z / n;
        double centre = (pHat + z2OverN / 2.0) / (1.0 + z2OverN);
        double halfWidth = z / (1.0 + z2OverN)
                * Math.sqrt(pHat * (1.0 - pHat) / n + z * z / (4.0 * n * n));
        return new Interval(clamp01(centre - halfWidth), clamp01(centre + halfWidth));
    }

    /**
     * Exact Clopper-Pearson interval at the given confidence level.
     *
     * <p>Endpoints are obtained from the incomplete beta quantile: the lower bound is the
     * {@code alpha/2} quantile of {@code Beta(k, n-k+1)} and the upper bound the
     * {@code 1-alpha/2} quantile of {@code Beta(k+1, n-k)}, with the degenerate cases
     * ({@code k = 0} giving lower 0, {@code k = n} giving upper 1) handled explicitly.
     */
    public static Interval clopperPearsonInterval(int successes, int trials, double confidence) {
        if (trials <= 0) {
            return new Interval(0.0, 1.0);
        }
        if (successes < 0 || successes > trials) {
            throw new IllegalArgumentException("require 0 <= successes <= trials");
        }
        double alpha = 1.0 - confidence;
        double lower = successes == 0
                ? 0.0
                : betaQuantile(alpha / 2.0, successes, trials - successes + 1);
        double upper = successes == trials
                ? 1.0
                : betaQuantile(1.0 - alpha / 2.0, successes + 1, trials - successes);
        return new Interval(clamp01(lower), clamp01(upper));
    }

    /**
     * Quantile of the {@code Beta(a, b)} distribution, by bisection on the regularised
     * incomplete beta function.
     *
     * <p>Bisection is chosen over a Newton iteration because the regularised incomplete beta
     * is monotonically increasing in {@code x} and is evaluated to near machine precision
     * here; bisection therefore cannot diverge and needs no good initial guess. Fifty
     * iterations of bisection resolve {@code x} far beyond the precision any decision uses.
     */
    public static double betaQuantile(double p, double a, double b) {
        if (p <= 0.0) {
            return 0.0;
        }
        if (p >= 1.0) {
            return 1.0;
        }
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            double value = SpecialFunctions.regularizedIncompleteBeta(mid, a, b);
            if (value < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /**
     * Two-sided critical value {@code z} for a confidence level.
     *
     * <p>Uses the inverse normal CDF obtained by bisection on {@link NormalDistribution#standardCdf(double)}.
     * Like {@link #betaQuantile} this is a robust bisection rather than an approximation
     * polynomial, so there is one consistent source of truth for tail probabilities.
     */
    public static double zForConfidence(double confidence) {
        if (!(confidence > 0.0) || !(confidence < 1.0)) {
            throw new IllegalArgumentException("confidence must lie in (0, 1), got " + confidence);
        }
        double target = 0.5 + confidence / 2.0;
        double lo = -10.0;
        double hi = 10.0;
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            if (NormalDistribution.standardCdf(mid) < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double clamp01(double v) {
        return Math.clamp(v, 0.0, 1.0);
    }

    /**
     * A closed interval, used for confidence intervals and credible intervals.
     */
    public record Interval(double lower, double upper) {

        public Interval {
            if (lower > upper) {
                throw new IllegalArgumentException("lower bound exceeds upper bound");
            }
        }

        public double width() {
            return upper - lower;
        }

        public boolean contains(double value) {
            return value >= lower && value <= upper;
        }

        @Override
        public String toString() {
            return String.format("[%.4f, %.4f]", lower, upper);
        }
    }
}
