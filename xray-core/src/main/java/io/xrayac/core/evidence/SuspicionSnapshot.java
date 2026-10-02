package io.xrayac.core.evidence;

import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.statistics.LogOdds;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The complete, explainable result of evaluating one player's analysis window.
 *
 * <h2>The four quantities, and why they are not the same thing</h2>
 * Conflating any two of these is the most common error in amateur anti-cheat, so they are
 * separate fields with separate meanings:
 *
 * <ul>
 *   <li><b>{@code posteriorLogOdds} / {@code suspicionScore}</b> — <i>how much does the observed
 *       behaviour favour the ore-informed hypothesis?</i> This is a statement about the data. It
 *       can be large on a handful of observations.</li>
 *   <li><b>{@code statisticalConfidence}</b> — <i>how much should we trust that statement?</i>
 *       This is a statement about the evidence base: how many observations, and how many
 *       independent kinds of signal. A high score with low confidence means "startling, but we
 *       have barely looked". A low score with high confidence means "we have looked carefully and
 *       found nothing". These are completely different situations and a moderator must be able to
 *       tell them apart.</li>
 *   <li><b>{@code evidenceStrength}</b> — the score and the confidence, banded together onto the
 *       conventional forensic scale, gated by minimum sample size and minimum independent signals.
 *       This is the field the decision policy acts on, precisely because it already encodes
 *       "enough data".</li>
 *   <li><b>{@code sampleSize} / {@code independentGroups}</b> — the raw inputs to confidence,
 *       shown so a moderator can see exactly what the judgement rests on.</li>
 * </ul>
 *
 * @param player                the subject
 * @param world                 the world the window belonged to
 * @param evaluatedAt           the instant of evaluation
 * @param priorLogOdds          belief before the observations
 * @param effectiveLogOdds      the observations' contribution after reliability, shrinkage and decay
 * @param posteriorLogOdds      prior plus effective
 * @param suspicionScore        {@code logistic(posteriorLogOdds)}, in {@code [0, 1]}
 * @param statisticalConfidence trust in the measurement, in {@code [0, 1]}
 * @param decayFactor           the mean time-decay weight applied, in {@code (0, 1]}
 * @param sampleSize            total independent observations across signal families
 * @param independentGroups     number of distinct signal families that contributed
 * @param evidenceStrength      the banded verdict
 * @param contributions         every component's individual result, retained for explanation
 */
public record SuspicionSnapshot(
        PlayerRef player,
        WorldId world,
        Instant evaluatedAt,
        double priorLogOdds,
        double effectiveLogOdds,
        double posteriorLogOdds,
        double suspicionScore,
        double statisticalConfidence,
        double decayFactor,
        int sampleSize,
        int independentGroups,
        EvidenceStrength evidenceStrength,
        List<EvidenceContribution> contributions) {

    public SuspicionSnapshot {
        contributions = List.copyOf(contributions);
    }

    /** Contributions ordered by how much they actually moved the verdict (descending). */
    public List<EvidenceContribution> contributionsByImpact() {
        return contributions.stream()
                .filter(EvidenceContribution::isInformative)
                .sorted(Comparator.comparingDouble(
                        (EvidenceContribution c) -> -Math.abs(c.weightedLogLikelihoodRatio())))
                .toList();
    }

    /** The contributions that argue <i>for</i> the ore-informed hypothesis. */
    public List<EvidenceContribution> supportingContributions() {
        return contributionsByImpact().stream()
                .filter(c -> c.weightedLogLikelihoodRatio() > 0)
                .toList();
    }

    /** The contributions that argue <i>against</i> it (evidence of legitimate play). */
    public List<EvidenceContribution> exculpatoryContributions() {
        return contributionsByImpact().stream()
                .filter(c -> c.weightedLogLikelihoodRatio() < 0)
                .toList();
    }

    /**
     * A structured, human-readable explanation answering "why is this player suspicious?".
     *
     * <p>Deliberately written as a body of evidence rather than a score: every line states the
     * quantity, the expectation it was compared against where relevant, and the direction of the
     * inference, so a moderator can check the reasoning rather than trust it.
     */
    public String explain() {
        StringBuilder sb = new StringBuilder();
        sb.append("Suspicion assessment for ").append(player.display())
                .append(" in ").append(world.key()).append('\n');
        sb.append(String.format("  verdict            : %s%n", evidenceStrength.label()));
        sb.append(String.format("  suspicion score    : %.4f%n", suspicionScore));
        sb.append(String.format("  statistical confid.: %.4f%n", statisticalConfidence));
        sb.append(String.format("  evidence (log-odds): %.3f (%.1f decibans)%n",
                posteriorLogOdds, LogOdds.toDecibans(posteriorLogOdds)));
        sb.append(String.format("  observations       : %d across %d independent signal(s)%n",
                sampleSize, independentGroups));
        sb.append(String.format("  time decay applied : %.3f%n", decayFactor));

        if (!contributionsByImpact().isEmpty()) {
            sb.append("  evidence:\n");
            for (EvidenceContribution c : contributionsByImpact()) {
                sb.append(String.format("    [%+7.3f] %s%n", c.weightedLogLikelihoodRatio(), c.explanation()));
            }
        }
        if (!exculpatoryContributions().isEmpty()) {
            sb.append("  note: ").append(exculpatoryContributions().size())
                    .append(" signal(s) argue in the player's favour and are included above.\n");
        }
        return sb.toString();
    }
}
