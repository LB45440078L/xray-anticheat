package io.xrayac.core.domain;

import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.PrincipalAxes;
import io.xrayac.core.geom.Vector3;
import java.util.List;
import java.util.Set;

/**
 * A reconstructed ore vein: the connected cluster of ore blocks surrounding a discovery, plus
 * the exposure classification of each block and geometric shape features.
 *
 * <p>Veins, not individual blocks, are the unit of evidence. The reason is statistical rather
 * than aesthetic: the blocks of one vein are placed together by world generation and are
 * therefore strongly correlated. Treating each block as an independent discovery would inflate
 * the sample size by the vein size and make every measurement look far more significant than it
 * is — the classic pseudo-replication error. Counting one observation per <i>vein</i> keeps the
 * independence assumption of the binomial and Poisson models honest.
 *
 * @param oreId          canonical ore identifier (for example {@code diamond})
 * @param discoveryBlock the block the player broke that led to this reconstruction
 * @param blocks         every ore block in the connected component (bounded by the analyser)
 * @param exposedBlocks  subset the player could have seen by ordinary play
 * @param hiddenBlocks   subset that was genuinely enclosed before the player's approach
 * @param aggregateExposure the most visible exposure state reached by any block of the vein; this,
 *                       not the discovery block's own state, is what the discovery is judged on
 * @param shape          geometric shape features derived from the block centres
 * @param truncated      true when the component exceeded the configured size bound and was cut
 *                       short; such a vein must not have its size compared against expectations
 */
public record VeinObservation(
        String oreId,
        BlockPos discoveryBlock,
        Set<BlockPos> blocks,
        Set<BlockPos> exposedBlocks,
        Set<BlockPos> hiddenBlocks,
        ExposureState aggregateExposure,
        VeinShape shape,
        boolean truncated) {

    public VeinObservation {
        if (oreId == null || oreId.isBlank()) {
            throw new IllegalArgumentException("oreId is required");
        }
        if (discoveryBlock == null) {
            throw new IllegalArgumentException("discoveryBlock is required");
        }
        if (aggregateExposure == null) {
            throw new IllegalArgumentException("aggregateExposure is required");
        }
        blocks = Set.copyOf(blocks);
        exposedBlocks = Set.copyOf(exposedBlocks);
        hiddenBlocks = Set.copyOf(hiddenBlocks);
        if (!blocks.containsAll(exposedBlocks) || !blocks.containsAll(hiddenBlocks)) {
            throw new IllegalArgumentException("exposure subsets must be drawn from the vein's blocks");
        }
        if (!java.util.Collections.disjoint(exposedBlocks, hiddenBlocks)) {
            throw new IllegalArgumentException("a block cannot be both exposed and hidden");
        }
    }

    public int size() {
        return blocks.size();
    }

    public int exposedCount() {
        return exposedBlocks.size();
    }

    public int hiddenCount() {
        return hiddenBlocks.size();
    }

    /**
     * Number of blocks whose exposure state we could actually determine.
     *
     * <p>Blocks in {@link ExposureState#UNKNOWN} appear in {@link #blocks()} but in neither the
     * exposed nor the hidden subset. Excluding them from the denominator is deliberate: if
     * unknown blocks counted as "not exposed" they would silently inflate apparent hiddenness
     * whenever world data is missing, which is exactly the direction of error that produces
     * false accusations.
     */
    public int classifiedCount() {
        return exposedBlocks.size() + hiddenBlocks.size();
    }

    /**
     * Fraction of the <i>classified</i> vein the player could see by ordinary play, in
     * {@code [0, 1]}.
     *
     * <p>Near 1 means the vein was largely in the open (a cave discovery); near 0 means it was
     * buried. The engine explicitly does <b>not</b> treat a low ratio as guilt on its own —
     * strip-miners routinely find buried veins — but the ratio feeds the expected-rate model
     * that sets the baseline for how often buried discoveries should occur.
     */
    public double exposedFraction() {
        int classified = classifiedCount();
        return classified == 0 ? 0.0 : (double) exposedBlocks.size() / classified;
    }

    /** Whether the discovery block itself was buried before the player's approach. */
    public boolean discoveryWasHidden() {
        return hiddenBlocks.contains(discoveryBlock);
    }

    /**
     * Whether the vein as a whole could have been found by ordinary means.
     *
     * <p>This is the question that matters, and it is not the same as
     * {@link #discoveryWasHidden()}: a player who can see one block of a vein can reasonably mine the
     * whole vein, so a vein with any visible member is one they could have found honestly. The
     * pipeline judges a discovery on this, not on the block that happened to be broken first.
     */
    public boolean isVisibleByOrdinaryPlay() {
        return aggregateExposure.isVisibleByOrdinaryPlay();
    }

    /**
     * Shape summary of a reconstructed vein.
     *
     * @param centroid            mean position of the vein's blocks
     * @param dominantAxis        principal direction of the vein (unit length, sign arbitrary)
     * @param linearity           {@code (l1-l2)/l1}; high for long thin veins
     * @param planarity           {@code (l2-l3)/l1}; high for flat, lens-shaped veins
     * @param extent              the largest pairwise span along the dominant axis, in blocks
     * @param rmsOffAxisDeviation RMS distance of blocks from the dominant axis, in blocks
     */
    public record VeinShape(
            Vector3 centroid,
            Vector3 dominantAxis,
            double linearity,
            double planarity,
            double extent,
            double rmsOffAxisDeviation) {

        /** A degenerate shape for veins too small to have a meaningful axis. */
        public static VeinShape ofSingleBlock(BlockPos pos) {
            return new VeinShape(pos.center(), Vector3.UP, 0.0, 0.0, 0.0, 0.0);
        }

        /** Derives shape features from a set of block positions using PCA. */
        public static VeinShape from(List<Vector3> centres) {
            if (centres.size() < 2) {
                return centres.isEmpty()
                        ? new VeinShape(Vector3.ZERO, Vector3.UP, 0.0, 0.0, 0.0, 0.0)
                        : ofSingleBlock(BlockPos.containing(centres.getFirst()));
            }
            PrincipalAxes axes = PrincipalAxes.of(centres);
            Vector3 axis = axes.dominantAxis();
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Vector3 c : centres) {
                double projection = c.subtract(axes.centroid()).dot(axis);
                min = Math.min(min, projection);
                max = Math.max(max, projection);
            }
            return new VeinShape(
                    axes.centroid(),
                    axis,
                    axes.linearity(),
                    axes.planarity(),
                    max - min,
                    axes.rmsOffAxisDeviation());
        }
    }
}
