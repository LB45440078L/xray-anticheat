package io.xrayac.core.statistics;

/**
 * Normal (Gaussian) distribution with mean {@code mu} and standard deviation {@code sigma}.
 *
 * <p>Used for two purposes:
 * <ul>
 *   <li>large-sample approximation of the Poisson tail, where the exact forward sum would
 *       underflow;</li>
 *   <li>a reference model for aggregate diagnostic quantities such as the robust (median /
 *       MAD based) z-score of an effect, reported in evidence explanations.</li>
 * </ul>
 *
 * <p>It is deliberately <b>not</b> the primary model for ore counts. Counts of a rare event
 * are skewed, and using a normal model on them would understate the surprise of an extreme
 * count, which is precisely the error that produces false positives on lucky players.
 */
public record NormalDistribution(double mu, double sigma) implements Distribution {

    public NormalDistribution {
        if (!Double.isFinite(mu)) {
            throw new IllegalArgumentException("mu must be finite, got " + mu);
        }
        if (!(sigma > 0.0) || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException("sigma must be finite and > 0, got " + sigma);
        }
    }

    /** Standard normal distribution. */
    public static NormalDistribution standard() {
        return new NormalDistribution(0.0, 1.0);
    }

    @Override
    public double logDensity(double x) {
        double z = (x - mu) / sigma;
        return -0.5 * z * z - Math.log(sigma) - 0.5 * Math.log(2.0 * Math.PI);
    }

    /** Cumulative distribution function {@code P(X <= x)}. */
    public double cdf(double x) {
        return standardCdf((x - mu) / sigma);
    }

    /**
     * Standard normal CDF, {@code Phi(z)}.
     *
     * <p>Evaluated via the Abramowitz &amp; Stegun 7.1.26 erf approximation, whose maximum
     * absolute error is about {@code 1.5e-7}. That is well below the resolution at which any
     * decision in this plugin is made (decisions turn on log-likelihood ratios of order 0.1
     * and above), and the alternative — a full precision erfc — would add meaningful
     * complexity for no decision-relevant gain. The limitation is stated here rather than
     * left implicit.
     */
    public static double standardCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /** Error function, A&S 7.1.26 (absolute error &lt; 1.5e-7). */
    public static double erf(double x) {
        double sign = Math.signum(x);
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double poly = t * (0.254829592
                + t * (-0.284496736
                + t * (1.421413741
                + t * (-1.453152027
                + t * 1.061405429))));
        double erf = 1.0 - poly * Math.exp(-ax * ax);
        return sign * erf;
    }

    @Override
    public double mean() {
        return mu;
    }

    @Override
    public double variance() {
        return sigma * sigma;
    }

    @Override
    public String describe() {
        return String.format("Normal(mu=%.4f, sigma=%.4f)", mu, sigma);
    }
}
