package io.xrayac.core.statistics;

/**
 * A probability distribution exposed to the analysis engine in log space.
 *
 * <p>Every model in this package is parameterised, immutable and side-effect free, so a
 * fitted model can be shared safely across analysis workers. The interface deliberately
 * exposes {@code logDensity}/{@code logPmf} rather than plain densities because the engine
 * only ever compares values multiplicatively (likelihood ratios), and log space keeps those
 * comparisons finite for the large sample sizes a busy server produces.
 */
public sealed interface Distribution
        permits PoissonDistribution, ExponentialDistribution, NormalDistribution {

    /**
     * Natural log of the probability density (continuous) or probability mass (discrete)
     * at {@code x}. Returns {@link Double#NEGATIVE_INFINITY} where the distribution assigns
     * zero probability.
     */
    double logDensity(double x);

    /** Expected value of the distribution. */
    double mean();

    /** Variance of the distribution. */
    double variance();

    /** A short human-readable description, used in evidence explanations. */
    String describe();
}
