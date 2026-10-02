package io.xrayac.core.statistics;

/**
 * Exponential distribution parameterised by a rate, used to model inter-arrival times.
 *
 * <p>The time (or distance, or block count) between successive hidden-ore discoveries is
 * modelled as the waiting time of a Poisson process with rate {@code rate}. This is the
 * natural counterpart to {@link PoissonDistribution}: if discoveries occur as a Poisson
 * process, the gaps between them are exponential, so the two models share assumptions and
 * the engine never mixes incompatible world views.
 *
 * <p>The mean is {@code 1 / rate} and {@code rate} is expressed in <b>per unit of the
 * chosen axis</b>. The engine instantiates it per axis (per block mined, per block of
 * travelled distance) so that a unit is never ambiguous.
 */
public record ExponentialDistribution(double rate) implements Distribution {

    public ExponentialDistribution {
        if (!(rate > 0.0) || !Double.isFinite(rate)) {
            throw new IllegalArgumentException("Exponential rate must be finite and > 0, got " + rate);
        }
    }

    @Override
    public double logDensity(double x) {
        if (x < 0.0) {
            return Double.NEGATIVE_INFINITY;
        }
        return Math.log(rate) - rate * x;
    }

    /**
     * Closed-form survival function {@code P(X > x)}, used for right-censored observations:
     * a player who stops mining has an interval that is only known to exceed some value, and
     * that censoring is handled by the survival function rather than being dropped (dropping
     * it would bias the estimate toward short intervals and manufacture suspicion).
     */
    public double survivalFunction(double x) {
        if (x < 0.0) {
            return 1.0;
        }
        return Math.exp(-rate * x);
    }

    @Override
    public double mean() {
        return 1.0 / rate;
    }

    @Override
    public double variance() {
        return 1.0 / (rate * rate);
    }

    @Override
    public String describe() {
        return String.format("Exponential(rate=%.6f, mean=%.3f)", rate, mean());
    }
}
