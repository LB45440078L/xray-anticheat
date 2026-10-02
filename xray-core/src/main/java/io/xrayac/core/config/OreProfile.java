package io.xrayac.core.config;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-ore parameters of the statistical model.
 *
 * <p>Each ore gets its own profile because their generation is genuinely different and sharing a
 * model between them would be wrong. Diamond occurs in small deep clusters, emerald in tiny
 * single-block veins confined to mountain biomes, and ancient debris in the nether as sparse
 * one-to-three-block blobs at extreme depths with a distinctive Y profile. A single set of
 * "ore" parameters would either drown emerald in noise or make ancient debris look impossible.
 *
 * <h2>On the two kinds of number in here</h2>
 * The <b>rate parameters</b> ({@code hiddenDiscoveryRatePerThousandBlocks} and
 * {@code oreInformedRateMultiplier}) are <i>priors</i>, not measured facts: they express what a
 * legitimate miner's yield and an ore-informed miner's yield are believed to look like. They are
 * intentionally conservative, and they are the parameters an administrator is most likely to want
 * to tune to their world type.
 *
 * <p>The <b>alignment probabilities</b> are geometric, not empirical, and are the load-bearing
 * quantities in the targeting model. They are derived from the fraction of directions in a cone,
 * with one important correction described at {@link #legitimateMoveAlignmentProbability()}.
 *
 * <p>The important structural property is that the engine never compares a single observation to
 * a threshold. It accumulates log-likelihood ratios, so a prior that is wrong by a factor of two
 * shifts the accumulated evidence slightly; it does not flip a verdict. What the priors must get
 * right is their <i>order of magnitude</i> and their <i>relative</i> ordering between ores.
 */
public record OreProfile(
        String oreId,
        String displayName,
        boolean enabled,
        Set<String> blockKeys,
        double hiddenDiscoveryRatePerThousandBlocks,
        double oreInformedRateMultiplier,
        int minY,
        int maxY,
        int typicalVeinSize,
        double expectedDistanceBetweenDiscoveries,
        double targetingAlignmentThresholdDegrees,
        double legitimateMoveAlignmentProbability,
        double informedMoveAlignmentProbability,
        double legitimateLookAlignmentProbability,
        double informedLookAlignmentProbability,
        double evidenceWeight) {

    public OreProfile {
        if (oreId == null || oreId.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("oreId and displayName are required");
        }
        blockKeys = Set.copyOf(blockKeys);
        if (!(hiddenDiscoveryRatePerThousandBlocks > 0.0)) {
            throw new IllegalArgumentException("hiddenDiscoveryRatePerThousandBlocks must be > 0 (ore=" + oreId + ")");
        }
        if (!(oreInformedRateMultiplier > 1.0)) {
            // A multiplier of 1 would make the ore-informed hypothesis indistinguishable from the
            // legitimate one, and a value below 1 would have the model argue that cheating reduces
            // finds. Both are configuration errors worth refusing at startup.
            throw new IllegalArgumentException("oreInformedRateMultiplier must be > 1 (ore=" + oreId + ")");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must not exceed maxY (ore=" + oreId + ")");
        }
        if (typicalVeinSize < 1) {
            throw new IllegalArgumentException("typicalVeinSize must be >= 1 (ore=" + oreId + ")");
        }
        if (!(targetingAlignmentThresholdDegrees > 0.0) || targetingAlignmentThresholdDegrees >= 90.0) {
            throw new IllegalArgumentException("alignment threshold must lie in (0, 90) degrees (ore=" + oreId + ")");
        }
        requireProbability(legitimateMoveAlignmentProbability, "legitimateMoveAlignmentProbability", oreId);
        requireProbability(informedMoveAlignmentProbability, "informedMoveAlignmentProbability", oreId);
        requireProbability(legitimateLookAlignmentProbability, "legitimateLookAlignmentProbability", oreId);
        requireProbability(informedLookAlignmentProbability, "informedLookAlignmentProbability", oreId);
        if (informedMoveAlignmentProbability <= legitimateMoveAlignmentProbability) {
            throw new IllegalArgumentException(
                    "the ore-informed hypothesis must predict more aligned movement than the legitimate "
                            + "one (ore=" + oreId + ")");
        }
        if (informedLookAlignmentProbability <= legitimateLookAlignmentProbability) {
            throw new IllegalArgumentException(
                    "the ore-informed hypothesis must predict more aligned looking than the legitimate "
                            + "one (ore=" + oreId + ")");
        }
        if (!(evidenceWeight > 0.0) || evidenceWeight > 1.0) {
            throw new IllegalArgumentException("evidenceWeight must lie in (0, 1] (ore=" + oreId + ")");
        }
    }

    private static void requireProbability(double value, String name, String oreId) {
        if (!(value > 0.0) || !(value < 1.0)) {
            throw new IllegalArgumentException(name + " must lie strictly in (0, 1) (ore=" + oreId + ")");
        }
    }

    /** Whether a Y coordinate lies within this ore's generated band. */
    public boolean isWithinGenerationBand(int y) {
        return y >= minY && y <= maxY;
    }

    public boolean observesBlock(String blockKey) {
        return blockKeys.contains(blockKey);
    }

    public static Builder builder(String oreId, String displayName) {
        return new Builder(oreId, displayName);
    }

    /**
     * Builds a profile with all parameters explicit.
     *
     * <p>A builder is used rather than a fifteen-argument constructor because profiles are written
     * by hand in code and in configuration; named parameters make an accidental swap of two
     * adjacent {@code double}s — which would be invisible at the call site and catastrophic in the
     * model — impossible to express.
     */
    public static final class Builder {

        private final String oreId;
        private final String displayName;
        private boolean enabled = true;
        private final Set<String> blockKeys = new LinkedHashSet<>();
        private double hiddenDiscoveryRatePerThousandBlocks;
        private double oreInformedRateMultiplier;
        private int minY = -64;
        private int maxY = 320;
        private int typicalVeinSize = 4;
        private double expectedDistanceBetweenDiscoveries = 400.0;
        private double targetingAlignmentThresholdDegrees = 30.0;
        private double legitimateMoveAlignmentProbability = 0.5;
        private double informedMoveAlignmentProbability = 0.8;
        private double legitimateLookAlignmentProbability = 0.067;
        private double informedLookAlignmentProbability = 0.5;
        private double evidenceWeight = 1.0;

        private Builder(String oreId, String displayName) {
            this.oreId = oreId;
            this.displayName = displayName;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder blockKeys(String... keys) {
            for (String key : keys) {
                blockKeys.add(key);
            }
            return this;
        }

        public Builder hiddenDiscoveryRatePerThousandBlocks(double rate) {
            this.hiddenDiscoveryRatePerThousandBlocks = rate;
            return this;
        }

        public Builder oreInformedRateMultiplier(double multiplier) {
            this.oreInformedRateMultiplier = multiplier;
            return this;
        }

        public Builder yRange(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
            return this;
        }

        public Builder typicalVeinSize(int size) {
            this.typicalVeinSize = size;
            return this;
        }

        public Builder expectedDistanceBetweenDiscoveries(double blocks) {
            this.expectedDistanceBetweenDiscoveries = blocks;
            return this;
        }

        public Builder targetingAlignmentThresholdDegrees(double degrees) {
            this.targetingAlignmentThresholdDegrees = degrees;
            return this;
        }

        public Builder moveAlignmentProbabilities(double legitimate, double informed) {
            this.legitimateMoveAlignmentProbability = legitimate;
            this.informedMoveAlignmentProbability = informed;
            return this;
        }

        public Builder lookAlignmentProbabilities(double legitimate, double informed) {
            this.legitimateLookAlignmentProbability = legitimate;
            this.informedLookAlignmentProbability = informed;
            return this;
        }

        public Builder evidenceWeight(double weight) {
            this.evidenceWeight = weight;
            return this;
        }

        public OreProfile build() {
            return new OreProfile(oreId, displayName, enabled, blockKeys,
                    hiddenDiscoveryRatePerThousandBlocks, oreInformedRateMultiplier,
                    minY, maxY, typicalVeinSize, expectedDistanceBetweenDiscoveries,
                    targetingAlignmentThresholdDegrees,
                    legitimateMoveAlignmentProbability, informedMoveAlignmentProbability,
                    legitimateLookAlignmentProbability, informedLookAlignmentProbability,
                    evidenceWeight);
        }
    }
}
