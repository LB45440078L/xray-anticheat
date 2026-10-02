package io.xrayac.core.evidence.component;

import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.config.OreProfile;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.evidence.EvidenceComponent;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceParameters;
import io.xrayac.core.statistics.LikelihoodRatios;
import java.util.Optional;

/**
 * Tests whether players appear to have been <i>heading for</i> buried ore before it became visible.
 *
 * <h2>Two signals, deliberately unequal</h2>
 * Two independent measurements are made for each buried ore the player reached, taken at a fixed
 * lookback distance before arrival (at arrival every player is adjacent to the ore and the angle is
 * meaningless):
 *
 * <ul>
 *   <li><b>Movement alignment</b> — did the player's direction of travel already point at the ore?
 *       This is deliberately given a <i>weak</i> model (0.5 under the legitimate hypothesis,
 *       0.8 under ore vision, matching OreProfile's defaults). A strip-miner walks in a straight
 *       line and mines whatever lies
 *       ahead, so the ore they eventually find is almost by construction in front of them. Rewarding
 *       this signal strongly would flag every strip-miner, which is the single most common false
 *       positive in naive detectors.</li>
 *   <li><b>Look alignment</b> — did the player's <i>camera</i> point at the ore through solid rock?
 *       This carries the sharper model (a geometric baseline of 0.067 against 0.5). The baseline is
 *       not a guess: it is the exact fraction of all directions contained in a 30-degree cone,
 *       {@code (1 - cos 30°)/2 ≈ 0.067}, i.e. the probability that an aimless heading lands inside
 *       the cone. A player repeatedly aiming into solid rock exactly where a vein sits is doing
 *       something the baseline says should be rare.</li>
 * </ul>
 *
 * <h2>Independence and restraint</h2>
 * Movement and look alignments are computed from different measurements (displacement versus
 * view direction) but arise from the same moment, so both are folded into the same independent
 * signal group. The sample size is the number of buried ores with a measurable approach — one
 * observation per vein, never per block — preserving the independence the binomial model requires.
 */
public final class OreTargetingComponent implements EvidenceComponent {

    public static final String ID = "ore-targeting";
    public static final String GROUP = "targeting";

    private static final double RELIABILITY = 0.75;

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

        int moveTrials = 0;
        int moveHits = 0;
        int lookTrials = 0;
        int lookHits = 0;
        double logLikelihoodRatio = 0.0;
        double weightSum = 0.0;
        StringBuilder detail = new StringBuilder();

        for (OreDiscovery discovery : window.hiddenDiscoveries()) {
            Optional<TrajectoryAnalysis.Approach> measurable = discovery.measurableApproach();
            if (measurable.isEmpty()) {
                continue;
            }
            OreProfile profile = profiles.profile(discovery.oreId()).orElse(null);
            if (profile == null || !profile.enabled()) {
                continue;
            }
            TrajectoryAnalysis.Approach approach = measurable.get();
            double threshold = profile.targetingAlignmentThresholdDegrees();

            double oreLogLikelihoodRatio = 0.0;

            moveTrials++;
            if (approach.movementAlignmentDegrees() <= threshold) {
                moveHits++;
            }
            oreLogLikelihoodRatio += LikelihoodRatios.binomialCount(
                    approach.movementAlignmentDegrees() <= threshold ? 1 : 0, 1,
                    profile.legitimateMoveAlignmentProbability(),
                    profile.informedMoveAlignmentProbability());

            if (!Double.isNaN(approach.lookAlignmentDegrees())) {
                lookTrials++;
                if (approach.lookAlignmentDegrees() <= threshold) {
                    lookHits++;
                }
                oreLogLikelihoodRatio += LikelihoodRatios.binomialCount(
                        approach.lookAlignmentDegrees() <= threshold ? 1 : 0, 1,
                        profile.legitimateLookAlignmentProbability(),
                        profile.informedLookAlignmentProbability());
            }

            logLikelihoodRatio += oreLogLikelihoodRatio * profile.evidenceWeight();
            weightSum += profile.evidenceWeight();

            detail.append(String.format(
                    "%s at %s: move %s (%.0f deg), look %s (%.0f deg); ",
                    profile.displayName(),
                    discovery.discoveryBlock(),
                    approach.movementAlignmentDegrees() <= threshold ? "aligned" : "not aligned",
                    approach.movementAlignmentDegrees(),
                    Double.isNaN(approach.lookAlignmentDegrees()) ? "unknown"
                            : (approach.lookAlignmentDegrees() <= threshold ? "aligned" : "not aligned"),
                    approach.lookAlignmentDegrees()));
        }

        if (moveTrials == 0) {
            return EvidenceContribution.none(ID, GROUP,
                    "no buried-ore approaches had enough preceding path history to measure a heading");
        }

        double weighted = weightSum > 0 ? logLikelihoodRatio / weightSum : logLikelihoodRatio;

        String explanation = String.format(
                "%d measurable approach(es) to buried ore: %d with the direction of travel already aimed "
                        + "at the ore, %d of %d with the camera aimed at the ore through solid rock. %s",
                moveTrials, moveHits, lookHits, lookTrials, detail.toString().trim());

        return new EvidenceContribution(
                ID, GROUP, weighted, moveTrials, RELIABILITY, explanation,
                EvidenceContribution.metrics(
                        "moveTrials", moveTrials,
                        "moveAligned", moveHits,
                        "lookTrials", lookTrials,
                        "lookAligned", lookHits));
    }
}
