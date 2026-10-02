package io.xrayac.core.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validates the statistical primitives against analytically known values.
 *
 * <p>These are the tests that matter most in the whole suite: every downstream conclusion is
 * a linear combination of these quantities, so an error here is invisible at the call site
 * but wrong everywhere. Where possible the expected value is a closed form (a probability
 * that can be reasoned out exactly) rather than a recorded number from a previous run, so the
 * test cannot "pass" merely because it remembers a buggy answer.
 */
class StatisticalPrimitivesTest {

    @Nested
    @DisplayName("SpecialFunctions")
    class SpecialFunctionsTest {

        @Test
        @DisplayName("logGamma matches known factorials")
        void logGammaKnownValues() {
            // Gamma(1)=1, Gamma(5)=24, Gamma(0.5)=sqrt(pi)
            assertThat(SpecialFunctions.logGamma(1.0)).isCloseTo(0.0, within(1e-12));
            assertThat(Math.exp(SpecialFunctions.logGamma(5.0))).isCloseTo(24.0, within(1e-9));
            assertThat(Math.exp(SpecialFunctions.logGamma(0.5)))
                    .isCloseTo(Math.sqrt(Math.PI), within(1e-12));
        }

        @Test
        @DisplayName("logGamma satisfies the recurrence Gamma(z+1)=z*Gamma(z)")
        void logGammaRecurrence() {
            for (double z = 0.5; z < 12.0; z += 0.5) {
                assertThat(SpecialFunctions.logGamma(z + 1.0))
                        .isCloseTo(SpecialFunctions.logGamma(z) + Math.log(z), within(1e-10));
            }
        }

        @Test
        @DisplayName("logFactorial is exact for small n and continuous thereafter")
        void logFactorial() {
            assertThat(Math.exp(SpecialFunctions.logFactorial(0))).isCloseTo(1.0, within(1e-12));
            assertThat(Math.exp(SpecialFunctions.logFactorial(5))).isCloseTo(120.0, within(1e-9));
            assertThat(Math.exp(SpecialFunctions.logFactorial(10))).isCloseTo(3628800.0, within(1e-3));
            // Above the lookup threshold it delegates to logGamma and must still agree.
            assertThat(SpecialFunctions.logFactorial(30))
                    .isCloseTo(SpecialFunctions.logGamma(31.0), within(1e-9));
        }

        @Test
        @DisplayName("logBinomialCoefficient matches Pascal's triangle")
        void logBinomialCoefficient() {
            assertThat(Math.exp(SpecialFunctions.logBinomialCoefficient(5, 2)))
                    .isCloseTo(10.0, within(1e-9));
            assertThat(Math.exp(SpecialFunctions.logBinomialCoefficient(6, 3)))
                    .isCloseTo(20.0, within(1e-9));
            assertThat(SpecialFunctions.logBinomialCoefficient(5, 6))
                    .isEqualTo(Double.NEGATIVE_INFINITY);
            assertThat(SpecialFunctions.logBinomialCoefficient(5, -1))
                    .isEqualTo(Double.NEGATIVE_INFINITY);
        }

        @Test
        @DisplayName("regularized incomplete beta is exact at known special cases")
        void regularizedIncompleteBeta() {
            // I_x(1,1) = x (uniform on [0,1])
            assertThat(SpecialFunctions.regularizedIncompleteBeta(0.3, 1, 1))
                    .isCloseTo(0.3, within(1e-9));
            // Symmetry: I_x(a,b) = 1 - I_{1-x}(b,a)
            double a = 2.5;
            double b = 4.0;
            double x = 0.37;
            assertThat(SpecialFunctions.regularizedIncompleteBeta(x, a, b))
                    .isCloseTo(1.0 - SpecialFunctions.regularizedIncompleteBeta(1.0 - x, b, a),
                            within(1e-9));
            assertThat(SpecialFunctions.regularizedIncompleteBeta(0.0, 2, 3)).isEqualTo(0.0);
            assertThat(SpecialFunctions.regularizedIncompleteBeta(1.0, 2, 3)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("LogOdds")
    class LogOddsTest {

        @Test
        @DisplayName("round-trips probability through log-odds")
        void roundTrip() {
            for (double p : new double[] {0.001, 0.05, 0.25, 0.5, 0.75, 0.95, 0.999}) {
                double back = LogOdds.toProbability(LogOdds.fromProbability(p));
                assertThat(back).isCloseTo(p, within(1e-9));
            }
        }

        @Test
        @DisplayName("log-odds of 1/2 is zero")
        void evenOdds() {
            assertThat(LogOdds.fromProbability(0.5)).isCloseTo(0.0, within(1e-12));
            assertThat(LogOdds.toProbability(0.0)).isCloseTo(0.5, within(1e-12));
        }

        @Test
        @DisplayName("clamping keeps probabilities away from the degenerate endpoints")
        void clamps() {
            assertThat(LogOdds.toProbability(1000.0)).isLessThan(1.0);
            assertThat(LogOdds.toProbability(-1000.0)).isGreaterThan(0.0);
            assertThat(LogOdds.toProbability(1000.0)).isGreaterThan(0.99);
            assertThat(LogOdds.accumulate(29.0, 29.0)).isEqualTo(LogOdds.MAX_MAGNITUDE);
        }
    }

    @Nested
    @DisplayName("PoissonDistribution")
    class PoissonTest {

        @Test
        @DisplayName("pmf matches the closed form and sums to one")
        void pmfClosure() {
            PoissonDistribution poisson = new PoissonDistribution(2.5);
            // P(X=0) = e^-2.5
            assertThat(poisson.pmf(0)).isCloseTo(Math.exp(-2.5), within(1e-12));
            // P(X=3) = e^-2.5 * 2.5^3 / 6
            assertThat(poisson.pmf(3))
                    .isCloseTo(Math.exp(-2.5) * Math.pow(2.5, 3) / 6.0, within(1e-12));
            double total = 0.0;
            for (int k = 0; k < 200; k++) {
                total += poisson.pmf(k);
            }
            assertThat(total).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("upper tail of a count far above the mean is tiny")
        void upperTail() {
            PoissonDistribution poisson = new PoissonDistribution(3.0);
            assertThat(poisson.upperTailPValue(0)).isEqualTo(1.0);
            // Seeing 30 events when the mean is 3 must be extremely unlikely.
            assertThat(poisson.upperTailPValue(30)).isLessThan(1e-14);
            // Seeing 3 when the mean is 3 is unremarkable.
            assertThat(poisson.upperTailPValue(3)).isGreaterThan(0.5);
        }

        @Test
        @DisplayName("upper tail falls back to the normal approximation without underflowing")
        void upperTailLargeLambda() {
            PoissonDistribution poisson = new PoissonDistribution(5000.0);
            double tail = poisson.upperTailPValue(9000);
            assertThat(tail).isGreaterThanOrEqualTo(0.0).isLessThan(1e-30);
        }

        @Test
        @DisplayName("rejects a non-positive rate")
        void rejectsInvalidLambda() {
            assertThatThrownBy(() -> new PoissonDistribution(0.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PoissonDistribution(-1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ExponentialDistribution")
    class ExponentialTest {

        @Test
        @DisplayName("survival function is the complement of the CDF")
        void survival() {
            ExponentialDistribution exp = new ExponentialDistribution(0.5);
            assertThat(exp.survivalFunction(0.0)).isCloseTo(1.0, within(1e-12));
            // P(X > mean) = e^-1 for any exponential
            assertThat(exp.survivalFunction(exp.mean())).isCloseTo(Math.exp(-1.0), within(1e-12));
            assertThat(exp.mean()).isCloseTo(2.0, within(1e-12));
        }
    }

    @Nested
    @DisplayName("LikelihoodRatios")
    class LikelihoodRatioTest {

        @Test
        @DisplayName("Poisson LLR is zero when both hypotheses predict the same rate")
        void poissonNeutralWhenRatesEqual() {
            assertThat(LikelihoodRatios.poissonCount(5, 1000.0, 0.002, 0.002))
                    .isCloseTo(0.0, within(1e-12));
        }

        @Test
        @DisplayName("Poisson LLR is positive for a count above the legitimate rate")
        void poissonPositiveForExcess() {
            double llr = LikelihoodRatios.poissonCount(20, 1000.0, 0.001, 0.01);
            assertThat(llr).isPositive();
        }

        @Test
        @DisplayName("Poisson LLR penalises H1 when exposure is large but counts are not")
        void poissonPenalisesLargeExposure() {
            // With 100k blocks mined, observing only the legitimate expectation must not be
            // treated as evidence of cheating.
            double llr = LikelihoodRatios.poissonCount(100, 100_000.0, 0.001, 0.01);
            assertThat(llr).isNegative();
        }

        @Test
        @DisplayName("binomial LLR is zero at p0 == p1")
        void binomialNeutral() {
            assertThat(LikelihoodRatios.binomialCount(3, 10, 0.3, 0.3))
                    .isCloseTo(0.0, within(1e-12));
        }

        @Test
        @DisplayName("binomial LLR prefers the hypothesis matching the observed proportion")
        void binomialPicksMatchingHypothesis() {
            // 9 successes in 10 trials: strong support for p1=0.9 over p0=0.1
            assertThat(LikelihoodRatios.binomialCount(9, 10, 0.1, 0.9)).isPositive();
            // 1 success in 10 trials: strong support for p0=0.1 over p1=0.9
            assertThat(LikelihoodRatios.binomialCount(1, 10, 0.1, 0.9)).isNegative();
        }

        @Test
        @DisplayName("exponential LLR is neutral when rates match")
        void exponentialNeutral() {
            List<LikelihoodRatios.IntervalObservation> intervals = List.of(
                    LikelihoodRatios.IntervalObservation.observed(1.0),
                    LikelihoodRatios.IntervalObservation.observed(2.0));
            assertThat(LikelihoodRatios.exponentialIntervals(intervals, 0.5, 0.5))
                    .isCloseTo(0.0, within(1e-12));
        }

        @Test
        @DisplayName("censored intervals contribute exposure without counting as events")
        void censoring() {
            List<LikelihoodRatios.IntervalObservation> intervals = List.of(
                    LikelihoodRatios.IntervalObservation.observed(1.0),
                    LikelihoodRatios.IntervalObservation.censored(10.0));
            // A single observed event plus a long censored wait should be far below the
            // rate the observation-only model would infer.
            double llr = LikelihoodRatios.exponentialIntervals(intervals, 0.1, 1.0);
            assertThat(llr).isNegative();
        }

        @Test
        @DisplayName("empty interval lists are uninformative")
        void emptyIntervals() {
            assertThat(LikelihoodRatios.exponentialIntervals(List.of(), 0.1, 0.2)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("BinomialModel")
    class BinomialModelTest {

        @Test
        @DisplayName("p-value of the most likely outcome is close to one")
        void pValueAtMode() {
            // 5 of 10 with p=0.5 is as unsurprising as it gets.
            assertThat(BinomialModel.exactTwoSidedPValue(5, 10, 0.5)).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("p-value of an extreme outcome is small")
        void pValueExtreme() {
            // All 12 successes under p=0.5: two-sided p = 2 * 0.5^12
            double expected = 2.0 * Math.pow(0.5, 12);
            assertThat(BinomialModel.exactTwoSidedPValue(12, 12, 0.5))
                    .isCloseTo(expected, within(1e-12));
        }

        @Test
        @DisplayName("Clopper-Pearson interval is conservative and brackets the estimate")
        void clopperPearsonBracketsEstimate() {
            var interval = BinomialModel.clopperPearsonInterval(3, 10, 0.95);
            assertThat(interval.contains(0.3)).isTrue();
            assertThat(interval.lower()).isLessThan(0.3);
            assertThat(interval.upper()).isGreaterThan(0.3);
            // Exact (Clopper-Pearson) interval is conservative: it must be at least as wide
            // as the Wilson score interval for the same confidence level. Note that it is NOT
            // necessarily wider than the naive Wald interval — Wald can be alarmingly narrow
            // near the boundaries, which is precisely why it is not used for decisions.
            assertThat(interval.width())
                    .isGreaterThanOrEqualTo(BinomialModel.wilsonInterval(3, 10, 0.95).width());
        }

        @Test
        @DisplayName("Clopper-Pearson endpoints are the degenerate 0 and 1 at the extremes")
        void clopperPearsonDegenerate() {
            assertThat(BinomialModel.clopperPearsonInterval(0, 10, 0.95).lower()).isEqualTo(0.0);
            assertThat(BinomialModel.clopperPearsonInterval(10, 10, 0.95).upper()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Wilson interval does not collapse to zero width at zero successes")
        void wilsonZeroSuccessNonDegenerate() {
            var interval = BinomialModel.wilsonInterval(0, 5, 0.95);
            assertThat(interval.lower()).isEqualTo(0.0);
            assertThat(interval.upper()).isGreaterThan(0.3);
        }

        @ParameterizedTest
        @DisplayName("z for 95% confidence is close to the textbook 1.959964")
        @ValueSource(doubles = {0.90, 0.95, 0.99})
        void zForConfidence(double confidence) {
            double z = BinomialModel.zForConfidence(confidence);
            double expected = switch ((int) Math.round(confidence * 100)) {
                case 90 -> 1.6448536;
                case 95 -> 1.9599640;
                case 99 -> 2.5758293;
                default -> throw new IllegalStateException();
            };
            assertThat(z).isCloseTo(expected, within(1e-5));
        }

        @Test
        @DisplayName("beta quantile inverts the regularized incomplete beta")
        void betaQuantileInverts() {
            double q = BinomialModel.betaQuantile(0.5, 2.0, 3.0);
            assertThat(SpecialFunctions.regularizedIncompleteBeta(q, 2.0, 3.0))
                    .isCloseTo(0.5, within(1e-9));
        }
    }

    @Nested
    @DisplayName("NormalDistribution")
    class NormalTest {

        @Test
        @DisplayName("standard normal CDF hits the canonical landmarks")
        void standardCdfLandmarks() {
            assertThat(NormalDistribution.standardCdf(0.0)).isCloseTo(0.5, within(1e-7));
            assertThat(NormalDistribution.standardCdf(1.0)).isCloseTo(0.8413447, within(1e-6));
            assertThat(NormalDistribution.standardCdf(-1.96)).isCloseTo(0.0249979, within(1e-6));
            assertThat(NormalDistribution.standardCdf(1.96)).isCloseTo(0.9750021, within(1e-6));
        }

        @Test
        @DisplayName("erf is odd")
        void erfIsOdd() {
            for (double x : new double[] {0.1, 0.5, 1.0, 2.0, 3.0}) {
                assertThat(NormalDistribution.erf(x))
                        .isCloseTo(-NormalDistribution.erf(-x), within(1e-12));
            }
        }
    }
}
