package io.xrayac.core.evidence.component;

import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.OreDiscovery;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.evidence.EvidenceComponent;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceParameters;
import io.xrayac.core.statistics.LikelihoodRatios;

/**
 * Tests the <b>mix</b> of buried and cave-exposed ore a player found, rather than the amount.
 *
 * <h2>The intuition, and why it is a genuinely separate signal</h2>
 * A player who explores the world — through caves, ravines and their own tunnels — finds a mixture:
 * some ore is lying in the open, some is buried. An ore-vision user has no reason to spend effort on
 * ore they could have found honestly, and tends to arrive disproportionately at the buried kind. So
 * the <i>proportion</i> of discoveries that were buried carries information that the <i>rate</i> of
 * discoveries does not.
 *
 * <p>This is the component that protects cave explorers. A player who spends a session in a large
 * deepslate cave system finds plenty of ore, but mostly exposed ore; their hidden fraction is low,
 * and this component returns <b>negative</b> evidence — a genuine, recorded argument in the player's
 * favour that offsets a superficially alarming discovery count elsewhere. Unusual-looking behaviour
 * that is explained by legitimate exploration reduces suspicion rather than being ignored.
 *
 * <p>The baseline hidden fraction is a configured parameter rather than a constant because it
 * depends on the server's terrain: a world with huge open cave systems produces far more exposed
 * discoveries than one dominated by solid stone.
 */
public final class ExposureMixComponent implements EvidenceComponent {

    public static final String ID = "exposure-mix";
    public static final String GROUP = "exposure-mix";

    /**
     * Reliability is moderate rather than high: the baseline hidden fraction is a configuration
     * choice that no server can know precisely, so this signal is trusted to move the verdict but
     * not to decide it.
     */
    private static final double RELIABILITY = 0.6;

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

        // Only discoveries that can honestly be placed on one side of the buried/visible divide are
        // counted. A discovery in somebody else's tunnel (CONDITIONALLY_EXPOSED) or one whose
        // surroundings could not be read (UNKNOWN) is excluded rather than counted as "exposed":
        // crediting an unreadable ore as though the player had found it lying in the open would hand
        // out exculpatory evidence for missing information, which is exactly the sort of
        // unearned leniency that makes a system untrustworthy in the opposite direction.
        int hidden = 0;
        int exposed = 0;
        for (OreDiscovery discovery : window.discoveries()) {
            if (discovery.discoveryExposure().isUnaccountablyHidden()) {
                hidden++;
            } else if (discovery.discoveryExposure().isVisibleByOrdinaryPlay()) {
                exposed++;
            }
        }
        int classified = hidden + exposed;

        if (classified == 0) {
            return EvidenceContribution.none(ID, GROUP,
                    "no ore discoveries were classified, so no buried/exposed mix can be compared");
        }

        double observedFraction = (double) hidden / classified;
        double llr = LikelihoodRatios.binomialCount(
                hidden, classified,
                parameters.legitimateHiddenFraction(),
                parameters.oreInformedHiddenFraction());

        String explanation = String.format(
                "%d of %d classified discoveries were buried (%.0f%%), against a %.0f%% baseline for "
                        + "legitimate play in this configuration; %s",
                hidden, classified, observedFraction * 100.0,
                parameters.legitimateHiddenFraction() * 100.0,
                llr > 0 ? "the mix leans toward buried ore" : "the mix is consistent with, or leans away from, "
                        + "buried ore");

        return new EvidenceContribution(
                ID, GROUP, llr, classified, RELIABILITY, explanation,
                EvidenceContribution.metrics(
                        "hiddenDiscoveries", hidden,
                        "exposedDiscoveries", exposed,
                        "observedHiddenFraction", observedFraction,
                        "baselineHiddenFraction", parameters.legitimateHiddenFraction()));
    }
}
