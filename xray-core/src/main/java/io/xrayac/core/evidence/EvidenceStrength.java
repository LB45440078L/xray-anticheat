package io.xrayac.core.evidence;

import io.xrayac.core.statistics.LogOdds;

/**
 * A qualitative band for the accumulated evidence, using the conventional forensic scale.
 *
 * <p>The bands are defined on <b>log-odds</b> (equivalently, decibans), not on the suspicion
 * percentage, for two reasons. First, a probability scale compresses exactly the range where the
 * interesting distinctions live: 0.99 and 0.999 are one band apart in probability but a full
 * order of magnitude apart in evidence. Second, stating a threshold in probability invites the
 * reader to treat the number as a frequency ("99% of players like this cheat"), which it is not;
 * log-odds is unambiguously a strength of evidence.
 *
 * <p>The scale is the standard one used in forensic science (roughly Jeffreys' / the ENFSI
 * verbal scale): a likelihood ratio of 10 is "moderate", 100 is "strong", 1000 is "very strong".
 * Using an established scale means a moderator's intuition from other domains transfers.
 *
 * <p>Critically, a band is only reached when the evidence is both strong <i>and</i> based on
 * enough observations. {@link #INSUFFICIENT} therefore describes an evidence base that is too
 * small to say anything, irrespective of how large the ratio happens to be — which is how the
 * system avoids condemning a player on three lucky finds.
 */
public enum EvidenceStrength {

    /** Too little data, or too few independent signals, to support any conclusion. */
    INSUFFICIENT,

    /** Barely worth noticing (likelihood ratio below ~10). */
    WEAK,

    /** Likelihood ratio around 10 or more. */
    MODERATE,

    /** Likelihood ratio around 100 or more. */
    STRONG,

    /** Likelihood ratio around 1000 or more. */
    VERY_STRONG;

    /** Log-odds threshold at or above which this band applies (ignoring sample-size gating). */
    public static EvidenceStrength fromLogOdds(double logOdds, int sampleSize, int independentGroups,
                                               int minimumSampleSize, int minimumGroups) {
        if (sampleSize < minimumSampleSize || independentGroups < minimumGroups) {
            return INSUFFICIENT;
        }
        if (logOdds >= Math.log(1000.0)) {
            return VERY_STRONG;
        }
        if (logOdds >= Math.log(100.0)) {
            return STRONG;
        }
        if (logOdds >= Math.log(10.0)) {
            return MODERATE;
        }
        if (logOdds >= Math.log(3.0)) {
            return WEAK;
        }
        // Anything below a likelihood ratio of 3 is not actionable. Critically, this branch also
        // absorbs evidence that points the <i>other</i> way: a cave explorer whose hidden fraction
        // argues against ore vision produces strongly negative log-odds, and labelling that "weak
        // evidence" would read, in a moderation report, as a mild accusation. INSUFFICIENT is the
        // truthful statement in both cases: there is no actionable evidence of cheating here.
        return INSUFFICIENT;
    }

    /** The likelihood ratio implied by this band's lower threshold, for human-facing reports. */
    public double lowerLikelihoodRatioBound() {
        return switch (this) {
            case INSUFFICIENT -> 1.0;
            case WEAK -> 3.0;
            case MODERATE -> 10.0;
            case STRONG -> 100.0;
            case VERY_STRONG -> 1000.0;
        };
    }

    /** A short human-readable label. */
    public String label() {
        return switch (this) {
            case INSUFFICIENT -> "insufficient evidence";
            case WEAK -> "weak evidence";
            case MODERATE -> "moderate evidence";
            case STRONG -> "strong evidence";
            case VERY_STRONG -> "very strong evidence";
        };
    }

    /** The posterior probability floor this band corresponds to, for display only. */
    public double indicativeProbability() {
        return LogOdds.toProbability(Math.log(lowerLikelihoodRatioBound()));
    }
}
