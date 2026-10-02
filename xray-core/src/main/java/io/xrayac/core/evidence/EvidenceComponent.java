package io.xrayac.core.evidence;

import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.config.OreProfileRegistry;

/**
 * One family of evidence derived from a player's analysis window.
 *
 * <p>Components are the extension point of the whole system: adding a new signal means adding one
 * implementation and registering it, not editing the engine. Each component must be a pure
 * function of its arguments, so that (a) it is trivially unit-testable, (b) the engine can run
 * components in any order, and (c) the same window always yields the same verdict, which is what
 * makes the system auditable after the fact.
 *
 * <p>Components must never throw on plausible input. Bad or absent data is reported as
 * {@link EvidenceContribution#none} with a reason, because a component that crashes the analysis
 * would take down the evaluation of every other signal for that player.
 */
public interface EvidenceComponent {

    /** Stable identifier, recorded with every contribution and used in the GUI and reports. */
    String id();

    /**
     * The independent-evidence group this component belongs to.
     *
     * <p>Two components that measure the same underlying signal — for example the count of hidden
     * discoveries and the waiting time between them, which are two views of one Poisson process —
     * must declare the same group. The engine counts distinct groups, not distinct components,
     * when deciding whether there is enough independent corroboration.
     */
    String independentGroup();

    /**
     * Evaluates this component.
     *
     * @param window     the frozen observations
     * @param profiles   per-ore parameters
     * @param parameters global engine parameters
     */
    EvidenceContribution evaluate(PlayerAnalysisWindow window,
                                  OreProfileRegistry profiles,
                                  EvidenceParameters parameters);
}
