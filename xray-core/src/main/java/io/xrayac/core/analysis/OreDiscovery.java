package io.xrayac.core.analysis;

import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import java.time.Instant;
import java.util.Optional;

/**
 * One ore vein encounter, with everything the statistical engine needs to reason about it.
 *
 * <p>This is the analysed form of a {@code VeinObservation}: the vein's structure, plus the
 * kinematic context (how the player arrived) and the intervening exposure (how much mining and
 * travel separated this discovery from the previous one).
 *
 * <p>All the "since previous" fields are measured in <b>units of player effort</b> — blocks mined
 * and blocks travelled — rather than in seconds. This is a deliberate and important choice:
 * wall-clock intervals are confounded by mining speed (haste, better tools, AFK pauses, server
 * lag), whereas "how much rock did this player have to move to find this ore" is a property of
 * the world and of the player's strategy, and it is directly comparable between players with
 * different equipment.
 *
 * @param oreId                          canonical ore id
 * @param discoveryBlock                 the ore block that was broken
 * @param time                           when it was broken
 * @param discoveryExposure              exposure of the broken block
 * @param veinSize                       blocks in the reconstructed vein
 * @param hiddenVeinBlocks               vein blocks that were unaccountably hidden
 * @param exposedVeinBlocks              vein blocks the player could have seen
 * @param blocksMinedSincePrevious       blocks broken since the previous ore discovery (any ore)
 * @param distanceTravelledSincePrevious blocks travelled since the previous discovery
 * @param approach                       geometric approach metrics, when measurable
 * @param evidenceDiscount               multiplier in {@code [0, 1]} from origin attribution;
 *                                       evidence about a vein in someone else's tunnel is
 *                                       discounted rather than credited
 */
public record OreDiscovery(
        String oreId,
        BlockPos discoveryBlock,
        Instant time,
        ExposureState discoveryExposure,
        int veinSize,
        int hiddenVeinBlocks,
        int exposedVeinBlocks,
        double blocksMinedSincePrevious,
        double distanceTravelledSincePrevious,
        TrajectoryAnalysis.Approach approach,
        double evidenceDiscount) {

    public OreDiscovery {
        if (oreId == null || oreId.isBlank()) {
            throw new IllegalArgumentException("oreId is required");
        }
        if (discoveryBlock == null || time == null || discoveryExposure == null) {
            throw new IllegalArgumentException("a discovery requires a block, a time and an exposure state");
        }
        if (veinSize < 1 || hiddenVeinBlocks < 0 || exposedVeinBlocks < 0) {
            throw new IllegalArgumentException("vein counts must be non-negative and size at least 1");
        }
        if (hiddenVeinBlocks + exposedVeinBlocks > veinSize) {
            throw new IllegalArgumentException("hidden + exposed blocks cannot exceed vein size");
        }
        if (blocksMinedSincePrevious < 0 || distanceTravelledSincePrevious < 0) {
            throw new IllegalArgumentException("effort since the previous discovery cannot be negative");
        }
        if (!(evidenceDiscount >= 0.0) || !(evidenceDiscount <= 1.0)) {
            throw new IllegalArgumentException("evidenceDiscount must lie in [0, 1]");
        }
        if (approach == null) {
            throw new IllegalArgumentException("approach must be present (use Approach.noData() when unknown)");
        }
    }

    /** True when the block the player broke was genuinely buried before their approach. */
    public boolean isHiddenDiscovery() {
        return discoveryExposure == ExposureState.HIDDEN;
    }

    /** Centre of the discovery block, for geometric comparisons. */
    public Vector3 center() {
        return discoveryBlock.center();
    }

    /**
     * How strongly this discovery speaks to the "the player could see this ore before mining it"
     * question, in {@code [0, 1]}: the exposure state's hiddenness weight, attenuated by the
     * origin discount.
     */
    public double evidentiaryWeight() {
        return discoveryExposure.hiddennessWeight() * evidenceDiscount;
    }

    public Optional<TrajectoryAnalysis.Approach> measurableApproach() {
        return approach.hadData() ? Optional.of(approach) : Optional.empty();
    }

    /**
     * Fraction of the classified vein whose blocks were each enclosed when the vein was inspected, in
     * {@code [0, 1]}.
     *
     * <p><b>This is a description of the vein's shape, not a verdict about the player.</b> It is the
     * per-block mix, so a vein of four blocks with one of them open reads as {@code 0.75} even though a
     * single open block is enough for the vein to be classified as visible and therefore discoverable by
     * ordinary play. The classification everything else uses is {@link #discoveryExposure()}; use this
     * only to explain how enclosed the vein was.
     */
    public double hiddenFraction() {
        int classified = hiddenVeinBlocks + exposedVeinBlocks;
        return classified == 0 ? 0.0 : (double) hiddenVeinBlocks / classified;
    }
}
