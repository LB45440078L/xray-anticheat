package io.xrayac.core.world;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.port.WorldView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link WorldView} for tests.
 *
 * <p>Defaults are chosen to model a plausible untouched world: any block that has not been
 * explicitly configured is solid opaque stone that was never excavated. Tests then carve out
 * exactly the situation they mean to test, which keeps each test's world readable as a
 * description of the scenario rather than a wall of setup.
 */
public final class FakeWorldView implements WorldView {

    private record Block(BlockKind kind, String key, MiningOrigin origin, long removedAt) {
    }

    private final Map<BlockPos, Block> blocks = new HashMap<>();
    private final Set<BlockPos> unloaded = new HashSet<>();
    private BlockKind defaultKind = BlockKind.OPAQUE_SOLID;

    /** Creates a view where every position is solid, natural stone. */
    public static FakeWorldView untouchedStone() {
        return new FakeWorldView();
    }

    /** Places an air pocket that was naturally generated (a cave). */
    public FakeWorldView naturalCavity(BlockPos pos) {
        blocks.put(pos, new Block(BlockKind.AIR, "minecraft:cave_air", MiningOrigin.NATURAL_TERRAIN, -1L));
        return this;
    }

    /** Places air that this player created at the given absolute epoch millis. */
    public FakeWorldView playerExcavated(BlockPos pos, long removedAtEpochMillis) {
        blocks.put(pos, new Block(BlockKind.AIR, "minecraft:air", MiningOrigin.PLAYER_CREATED, removedAtEpochMillis));
        return this;
    }

    /** Places air that another player created at the given absolute epoch millis. */
    public FakeWorldView otherPlayerExcavated(BlockPos pos, long removedAtEpochMillis) {
        blocks.put(pos,
                new Block(BlockKind.AIR, "minecraft:air", MiningOrigin.OTHER_PLAYER_CREATED, removedAtEpochMillis));
        return this;
    }

    /** Places air of unknown provenance. */
    public FakeWorldView unattributedAir(BlockPos pos) {
        blocks.put(pos, new Block(BlockKind.AIR, "minecraft:air", MiningOrigin.UNKNOWN, -1L));
        return this;
    }

    /** Marks a position as unloaded. */
    public FakeWorldView unload(BlockPos pos) {
        unloaded.add(pos);
        return this;
    }

    /** Explicitly sets a block kind (for example glass, which transmits sight but is solid). */
    public FakeWorldView block(BlockPos pos, BlockKind kind, String key) {
        blocks.put(pos, new Block(kind, key, MiningOrigin.NATURAL_TERRAIN, -1L));
        return this;
    }

    /** Makes the entire world consist of the given kind (useful for "all air" scenarios). */
    public FakeWorldView withDefaultKind(BlockKind kind) {
        this.defaultKind = kind;
        return this;
    }

    @Override
    public boolean isLoaded(WorldId world, BlockPos pos) {
        return !unloaded.contains(pos);
    }

    @Override
    public BlockKind kindAt(WorldId world, BlockPos pos) {
        Block b = blocks.get(pos);
        return b != null ? b.kind() : defaultKind;
    }

    @Override
    public String blockKeyAt(WorldId world, BlockPos pos) {
        return blocks.containsKey(pos) ? blocks.get(pos).key() : "minecraft:stone";
    }

    @Override
    public Optional<MiningOrigin> originAt(WorldId world, BlockPos pos) {
        Block b = blocks.get(pos);
        if (b != null) {
            return Optional.of(b.origin());
        }
        return Optional.of(MiningOrigin.NATURAL_TERRAIN);
    }

    @Override
    public long removalEpochMillis(WorldId world, BlockPos pos) {
        Block b = blocks.get(pos);
        return b != null ? b.removedAt() : -1L;
    }
}
