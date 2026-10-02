package io.xrayac.core.evidence.component;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.config.OreProfile;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.evidence.EvidenceComponent;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceParameters;
import io.xrayac.core.statistics.LikelihoodRatios;
import java.util.List;

/**
 * Tests the <i>waiting times</i> between hidden-ore discoveries, in blocks of effort.
 *
 * <h2>Why this complements the discovery rate</h2>
 * A count tells you how many were found; a waiting time tells you how they were distributed. The
 * two detect different evasions. A rate-cheater who deliberately pauses between finds has a
 * normal-looking count but a suspiciously regular or long-minimum gap pattern. An ore-vision user
 * who beelines to every vein produces waiting times clustered tightly just above the cost of the
 * shortest possible tunnel to a neighbouring vein — the gaps are too uniform and too short at
 * their lower end.
 *
 * <p>The gaps are modelled as exponential, the correct waiting-time distribution for the Poisson
 * process the rate component assumes, so the two components share a set of assumptions rather than
 * fighting each other.
 *
 * <h2>Sample-size honesty</h2>
 * The number of <i>observed</i> (uncensored) intervals is reported as the sample size, and the
 * trailing interval — the effort from the last discovery to the end of the window, during which no
 * discovery occurred — is included as a right-censored observation. Discarding that trailing
 * interval would shorten the observed waits and bias this component toward suspicion, which is
 * exactly the kind of one-sided error that must be avoided when the consequence is an accusation.
 *
 * <p>This component shares its independent-signal group with {@link HiddenDiscoveryRateComponent}:
 * both are views of one underlying discovery process.
 */
public final class InterDiscoveryWaitingComponent implements EvidenceComponent {

    public static final String ID = "inter-discovery-waiting";
    public static final String GROUP = HiddenDiscoveryRateComponent.GROUP;

    private static final double RELIABILITY = 0.7;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String independentGroup() {
        return GROUP;
    }

    @Override
    public EvidenceContribution evaluate(PlayerAnalysisWindow window,
                                         OreProfileRegistry profiles,
                                         EvidenceParameters parameters) {

        if (window.hiddenCount() == 0) {
            return EvidenceContribution.none(ID, GROUP,
                    "no hidden ore discoveries, so there are no waiting times to analyse");
        }

        // Use the ore the player actually found buried most often; mixing ores with different
        // generation rates into one waiting-time distribution would produce a mixture whose
        // exponential form is meaningless.
        OreProfile dominant = dominantHiddenOre(window, profiles);
        if (dominant == null) {
            return EvidenceContribution.none(ID, GROUP,
                    "no configured ore profile matched the player's discoveries");
        }

        List<LikelihoodRatios.IntervalObservation> intervals = window.hiddenDiscoveryIntervals();
        int observedIntervals = (int) intervals.stream()
                .filter(i -> !i.rightCensored())
                .count();
        if (observedIntervals == 0) {
            return EvidenceContribution.none(ID, GROUP,
                    "no completed waiting intervals were available");
        }

        double legitimateRatePerBlock = dominant.hiddenDiscoveryRatePerThousandBlocks() / 1000.0;
        double informedRatePerBlock = legitimateRatePerBlock * dominant.oreInformedRateMultiplier();

        double llr = LikelihoodRatios.exponentialIntervals(
                intervals, legitimateRatePerBlock, informedRatePerBlock);

        double totalEffort = intervals.stream().mapToDouble(LikelihoodRatios.IntervalObservation::value).sum();
        double observedMeanWait = totalEffort / observedIntervals;
        double expectedMeanWait = 1.0 / legitimateRatePerBlock;

        String explanation = String.format(
                "mean effort between buried %s discoveries was %.0f blocks, against %.0f blocks expected "
                        + "at the legitimate yield for this configuration (%d completed interval(s), "
                        + "the trailing interval treated as censored)",
                dominant.displayName(), observedMeanWait, expectedMeanWait, observedIntervals);

        return new EvidenceContribution(
                ID, GROUP, llr, observedIntervals, RELIABILITY, explanation,
                EvidenceContribution.metrics(
                        "observedMeanWaitBlocks", observedMeanWait,
                        "expectedMeanWaitBlocks", expectedMeanWait,
                        "observedIntervals", observedIntervals,
                        "censoredIntervals", intervals.size() - observedIntervals));
    }

    private OreProfile dominantHiddenOre(PlayerAnalysisWindow window, OreProfileRegistry profiles) {
        OreProfile best = null;
        int bestCount = -1;
        for (OreDiscovery discovery : window.hiddenDiscoveries()) {
            OreProfile profile = profiles.profile(discovery.oreId()).orElse(null);
            if (profile == null || !profile.enabled()) {
                continue;
            }
            int count = window.hiddenCountOf(discovery.oreId());
            if (count > bestCount) {
                bestCount = count;
                best = profile;
            }
        }
        return best;
    }
}
