package io.xrayac.core.evidence.component;

import io.xrayac.core.analysis.PlayerAnalysisWindow;
import io.xrayac.core.analysis.TrajectoryAnalysis;
import io.xrayac.core.config.OreProfileRegistry;
import io.xrayac.core.evidence.EvidenceComponent;
import io.xrayac.core.evidence.EvidenceContribution;
import io.xrayac.core.evidence.EvidenceParameters;

/**
 * Contributes a small, deliberately bounded amount of evidence from the shape of the player's path.
 *
 * <h2>Why the contribution is capped hard</h2>
 * Tunnel geometry is the classic false-positive generator. Strip-mining produces long, straight,
 * highly efficient tunnels; so does ore-seeking. Branch-mining produces a regular grid; so does a
 * systematic cheater. There is no shape a cheater must adopt and none a legitimate player must
 * avoid, so geometry can never be decisive on its own.
 *
 * <p>Its actual value is as a <i>modulator</i>: a suspicious discovery rate achieved along a path
 * with near-perfect directional efficiency (heading straight from one buried vein to the next, with
 * almost no wasted motion) is more informative than the same rate achieved while wandering. This
 * component therefore adds at most a likelihood ratio of 2 in either direction — a small nudge that
 * can never carry a verdict — and declares a low reliability to match.
 *
 * <p>Correspondingly, its sample size is 1 per window: the path is one observation, and treating its
 * hundreds of vertices as independent samples would let this weak signal dominate the engine's
 * sample-size accounting and thereby inflate confidence in everything else.
 */
public final class TunnelGeometryComponent implements EvidenceComponent {

    public static final String ID = "tunnel-geometry";
    public static final String GROUP = "geometry";

    private static final double RELIABILITY = 0.4;

    /** Paths scoring at or below this on the composite linearity measure are unremarkable. */
    private static final double NEUTRAL_LINEARITY = 0.75;

    /** Log-likelihood ratio cap, corresponding to a likelihood ratio of at most 2. */
    private static final double MAX_ABS_LOG_LIKELIHOOD_RATIO = Math.log(2.0);

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

        TrajectoryAnalysis.Geometry geometry = window.geometry();
        if (!geometry.hasData()) {
            return EvidenceContribution.none(ID, GROUP,
                    "the player's path was too short to describe its shape");
        }

        // Composite of two orthogonal notions of "directness": how much of the path's variance lies
        // along one axis (straightness), and how much of the distance travelled made net progress
        // (efficiency). A path can be straight without being efficient (walking straight, pausing,
        // walking straight again) and efficient without being straight overall.
        double linearity = 0.5 * geometry.straightness() + 0.5 * geometry.pathEfficiency();
        double excess = linearity - NEUTRAL_LINEARITY;
        double logLikelihoodRatio = Math.clamp(excess * 1.2, -MAX_ABS_LOG_LIKELIHOOD_RATIO,
                MAX_ABS_LOG_LIKELIHOOD_RATIO);

        String explanation = String.format(
                "path of %.0f blocks over %d point(s): straightness %.2f, path efficiency %.2f, "
                        + "turning %.0f deg per 100 blocks, vertical component %.0f%%. Geometry is weak "
                        + "evidence by design and is capped at a likelihood ratio of 2.",
                geometry.pathLength(), geometry.pointCount(), geometry.straightness(),
                geometry.pathEfficiency(), geometry.turnDensity(), geometry.verticalShare() * 100.0);

        return new EvidenceContribution(
                ID, GROUP, logLikelihoodRatio, 1, RELIABILITY, explanation,
                EvidenceContribution.metrics(
                        "pathLength", geometry.pathLength(),
                        "straightness", geometry.straightness(),
                        "pathEfficiency", geometry.pathEfficiency(),
                        "turnDensity", geometry.turnDensity(),
                        "verticalShare", geometry.verticalShare(),
                        "verticalDrift", geometry.verticalDrift(),
                        "linearity", linearity));
    }
}
