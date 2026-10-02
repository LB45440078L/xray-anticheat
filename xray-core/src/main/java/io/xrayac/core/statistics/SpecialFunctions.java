package io.xrayac.core.statistics;

/**
 * Special functions required by the statistical models, implemented without an external
 * maths dependency.
 *
 * <p>Every routine here operates in <b>log space</b>. Counts in an anti-cheat context are
 * routinely large (blocks mined can reach six figures over a session), and the binomial /
 * multinomial coefficients, factorials and Poisson masses involved overflow {@code double}
 * long before that. Working with log-gamma and log-coefficients keeps the intermediate
 * quantities finite and preserves relative accuracy, which is what matters because the
 * engine ultimately compares ratios (likelihood ratios) rather than absolute probabilities.
 */
public final class SpecialFunctions {

    /**
     * Lanczos approximation coefficients (g = 7, n = 9), giving roughly 15 significant
     * digits for the log-gamma function across the positive real axis.
     */
    private static final double[] LANCZOS = {
            0.99999999999980993,
            676.5203681218851,
            -1259.1392167224028,
            771.32342877765313,
            -176.61502916214059,
            12.507343278686905,
            -0.13857109526572012,
            9.9843695780195716e-6,
            1.5056327351493116e-7};

    private static final double HALF_LN_2PI = 0.5 * Math.log(2.0 * Math.PI);

    private SpecialFunctions() {
    }

    /**
     * Natural logarithm of the gamma function for {@code z > 0}, via the Lanczos
     * approximation with the reflection formula for {@code z < 0.5}.
     *
     * @throws IllegalArgumentException for a non-positive integer (the gamma function has
     *         poles there and no finite value exists)
     */
    public static double logGamma(double z) {
        if (z <= 0.0 && Math.abs(z - Math.rint(z)) < 1e-12) {
            throw new IllegalArgumentException("logGamma is undefined at non-positive integer " + z);
        }
        if (z < 0.5) {
            // Reflection: Gamma(z)Gamma(1-z) = pi / sin(pi z)
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * z))) - logGamma(1.0 - z);
        }
        double zz = z - 1.0;
        double sum = LANCZOS[0];
        for (int i = 1; i < LANCZOS.length; i++) {
            sum += LANCZOS[i] / (zz + i);
        }
        double t = zz + 7.5;
        return HALF_LN_2PI + (zz + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    /**
     * {@code log(n!)}. Exact for small {@code n}, Lanczos-based for larger values.
     */
    public static double logFactorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("factorial of a negative number: " + n);
        }
        if (n < 2) {
            return 0.0;
        }
        // A small lookup avoids Lanczos round-off for the tiny arguments where the exact
        // integer value is representable, keeping the classic test cases exact.
        if (n <= 20) {
            double acc = 0.0;
            for (int i = 2; i <= n; i++) {
                acc += Math.log(i);
            }
            return acc;
        }
        return logGamma(n + 1.0);
    }

    /**
     * {@code log(C(n, k))}, the log binomial coefficient. Returns {@code -inf} for
     * {@code k < 0} or {@code k > n} (the coefficient is zero there).
     */
    public static double logBinomialCoefficient(int n, int k) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be non-negative: " + n);
        }
        if (k < 0 || k > n) {
            return Double.NEGATIVE_INFINITY;
        }
        return logFactorial(n) - logFactorial(k) - logFactorial(n - k);
    }

    /**
     * Log of the beta function {@code B(a, b)} for {@code a, b > 0}.
     */
    public static double logBeta(double a, double b) {
        if (a <= 0.0 || b <= 0.0) {
            throw new IllegalArgumentException("logBeta requires positive arguments: " + a + ", " + b);
        }
        return logGamma(a) + logGamma(b) - logGamma(a + b);
    }

    /**
     * Log of the regularised incomplete beta function ratio {@code I_x(a, b)}.
     *
     * <p>Needed for the exact (Clopper-Pearson) binomial confidence interval. Implemented by
     * the standard continued fraction evaluated with the modified Lentz algorithm, which is
     * the numerically stable choice; a naive series would lose accuracy near the tails, and
     * the tails are precisely where a false-positive decision lives.
     */
    public static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        double front = Math.exp(
                logGamma(a + b) - logGamma(a) - logGamma(b)
                        + a * Math.log(x) + b * Math.log1p(-x));
        // Use the symmetry relation to ensure the continued fraction converges quickly.
        if (x > (a + 1.0) / (a + b + 2.0)) {
            return 1.0 - regularizedIncompleteBeta(1.0 - x, b, a);
        }
        return front * betaContinuedFraction(x, a, b) / a;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        final int maxIterations = 400;
        final double tiny = 1e-300;
        final double eps = 1e-14;

        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;

        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= maxIterations; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            h *= d * c;

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;

            if (Math.abs(delta - 1.0) < eps) {
                break;
            }
        }
        return h;
    }
}
