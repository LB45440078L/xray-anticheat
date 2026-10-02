package io.xrayac.core.geom;

import java.util.List;

/**
 * Integer block coordinate within a single world/dimension.
 *
 * <p>Deliberately world-agnostic: the world identity travels alongside a stream of these
 * via {@code WorldId}, so that coordinate arithmetic never needs to carry a world object.
 *
 * <p>The six orthogonal neighbour offsets are the canonical ones used by the exposure
 * analyser when probing whether a block face is open to a natural cavity.
 */
public record BlockPos(int x, int y, int z) {

    /** The six face-adjacent offsets, in a stable order (used for deterministic analysis). */
    public static final List<BlockPos> ORTHOGONAL_OFFSETS = List.of(
            new BlockPos(1, 0, 0),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0),
            new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(0, 0, -1));

    /**
     * The 26 surrounding offsets (Chebyshev distance 1), including diagonals.
     *
     * <p>Used where a semi-transparent block or a thin opening can expose an ore
     * diagonally; the exposure analyser consults this only for the configured
     * "diagonal visibility" rules, never by default.
     */
    public static final List<BlockPos> NEIGHBOURHOOD_26 = buildNeighbourhood26();

    public static BlockPos of(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Floors a continuous position to the containing block. */
    public static BlockPos containing(Vector3 v) {
        return new BlockPos(
                (int) Math.floor(v.x()),
                (int) Math.floor(v.y()),
                (int) Math.floor(v.z()));
    }

    public BlockPos add(BlockPos o) {
        return new BlockPos(x + o.x, y + o.y, z + o.z);
    }

    public BlockPos add(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    /** The centre of this block in continuous world space. */
    public Vector3 center() {
        return new Vector3(x + 0.5, y + 0.5, z + 0.5);
    }

    public int manhattanDistance(BlockPos o) {
        return Math.abs(x - o.x) + Math.abs(y - o.y) + Math.abs(z - o.z);
    }

    public double euclideanDistance(BlockPos o) {
        long dx = (long) x - o.x;
        long dy = (long) y - o.y;
        long dz = (long) z - o.z;
        return Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
    }

    /** Chebyshev distance: the number of king moves between two blocks. */
    public int chebyshevDistance(BlockPos o) {
        return Math.max(Math.abs(x - o.x), Math.max(Math.abs(y - o.y), Math.abs(z - o.z)));
    }

    private static List<BlockPos> buildNeighbourhood26() {
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    out.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        return List.copyOf(out);
    }
}
