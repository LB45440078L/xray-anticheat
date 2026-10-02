package io.xrayac.core.evidence.component;

import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.config.OreProfile;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.evidence.EvidenceComponent;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceParameters;
import io.xrayac.core.statistics.LikelihoodRatios;

/**
 * Tests whether the player found more genuinely buried ore than a legitimate miner's yield would
 * predict, given how much rock they moved.
 *
 * <h2>The model</h2>
 * Hidden-ore discoveries are modelled as a Poisson process with rate proportional to blocks mined.
 * Under {@code H0} the rate is the ore's configured legitimate yield; under {@code H1} it is that
 * yield multiplied by the ore's informed-rate multiplier.
 *
 * <p>Exposure is measured in <b>blocks mined</b>, not in time. This is what stops the component
 * from accusing fast players, players with Efficiency pickaxes, or players who simply played
 * longer: the comparison is always "finds per block of rock moved", which is a property of the
 * terrain and of the player's strategy.
 *
 * <h2>Why this alone is never enough</h2>
 * A lucky player mining a rich area genuinely can beat the expected rate by a large factor, and a
 * player exploring a vast cave system moves comparatively little rock per ore found. The nominal
 * log-likelihood ratio this component produces is therefore scaled by the engine's sample-size
 * shrinkage, and its signal family is shared with the inter-discovery timing component so that the
 * two cannot masquerade as independent corroboration.
 */
public final class HiddenDiscoveryRateComponent implements EvidenceComponent {

    public static final String ID = "hidden-discovery-rate";
    public static final String GROUP = "discovery-rate";

    /** Prior uncertainty in the rate model itself, reflected as a modest reliability. */
    private static final double RELIABILITY = 0.8;

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

        double exposure = window.blocksMined();
        if (exposure <= 0.0) {
            return EvidenceContribution.none(ID, GROUP,
                    "no blocks were mined in this window, so no discovery rate can be estimated");
        }

        double totalLogLikelihoodRatio = 0.0;
        double weightSum = 0.0;
        int hiddenObserved = 0;
        int oresConsidered = 0;
        StringBuilder detail = new StringBuilder();

        for (String oreId : profiles.enabledOreIds()) {
            OreProfile profile = profiles.profile(oreId).orElse(null);
            if (profile == null || !profile.enabled()) {
                continue;
            }
            int observed = window.hiddenCountOf(oreId);
            double legitimateRatePerBlock = profile.hiddenDiscoveryRatePerThousandBlocks() / 1000.0;
            double expected = legitimateRatePerBlock * exposure;
            double informedRatePerBlock = legitimateRatePerBlock * profile.oreInformedRateMultiplier();

            double llr = LikelihoodRatios.poissonCount(
                    observed, exposure, legitimateRatePerBlock, informedRatePerBlock);

            totalLogLikelihoodRatio += llr * profile.evidenceWeight();
            weightSum += profile.evidenceWeight();
            hiddenObserved += observed;
            oresConsidered++;

            detail.append(String.format(
                    "%s: %d hidden discovered vs %.2f expected at the legitimate rate; ",
                    profile.displayName(), observed, expected));
        }

        if (oresConsidered == 0 || hiddenObserved == 0) {
            // With no buried-ore discoveries at all there is no rate to compare: the honest
            // statement is that this signal is silent, not that it argues for innocence. Counting
            // a zero observed against a positive expectation as exculpatory evidence would give a
            // player credit merely for not having been observed yet.
            return EvidenceContribution.none(ID, GROUP,
                    "no hidden ore discoveries were recorded, so the discovery-rate model is silent");
        }

        double weighted = weightSum > 0 ? totalLogLikelihoodRatio / weightSum : totalLogLikelihoodRatio;

        String explanation = String.format(
                "%d buried ore discovery(ies) over %.0f blocks mined, against the legitimate yield "
                        + "model for this configuration. %s",
                hiddenObserved, exposure, detail.toString().trim());

        return new EvidenceContribution(
                ID, GROUP, weighted, hiddenObserved, RELIABILITY, explanation,
                EvidenceContribution.metrics(
                        "blocksMined", exposure,
                        "hiddenDiscoveries", hiddenObserved,
                        "rawLogLikelihoodRatio", totalLogLikelihoodRatio));
    }
}
