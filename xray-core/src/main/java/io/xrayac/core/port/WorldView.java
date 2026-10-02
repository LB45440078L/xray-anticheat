package io.xrayac.core.port;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.world.BlockKind;
import java.util.Optional;

/**
 * Read-only access to world state, as the analytical core needs it.
 *
 * <p>This is an <b>outbound port</b> in the hexagonal-architecture sense: the core defines the
 * interface it needs, and the Paper adapter implements it by reading chunks on the server
 * thread and handing back plain values. The core never holds a Bukkit {@code World} or
 * {@code Block}, so it can be exercised in tests against an in-memory implementation, and it
 * cannot accidentally trigger a synchronous chunk load from an analysis thread.
 *
 * <p><b>Threading contract.</b> Implementations are not required to be thread-safe. All calls
 * happen on the server thread, during observation collection, before the resulting immutable
 * values are dispatched to a worker. Implementations must never block on I/O.
 */
public interface WorldView {

    /**
     * Whether the chunk containing {@code pos} is currently loaded, so that a truthful answer
     * about the block is possible. When false, callers must degrade to
     * {@link BlockKind#UNKNOWN}/{@code ExposureState.UNKNOWN} rather than guessing: asking a
     * server for a block in an unloaded chunk would either load it (a performance disaster) or
     * return a default that looks like solid stone and would fabricate "hidden ore" evidence.
     */
    boolean isLoaded(WorldId world, BlockPos pos);

    /** Visibility classification of the block at {@code pos}. */
    BlockKind kindAt(WorldId world, BlockPos pos);

    /**
     * Stable namespaced material key at {@code pos} (for example {@code minecraft:stone}), or
     * {@code null} when the chunk is unloaded.
     */
    String blockKeyAt(WorldId world, BlockPos pos);

    /**
     * Who removed the block that used to occupy {@code pos}, when the block is currently air
     * and its removal is attributable.
     *
     * <p>Returns {@link MiningOrigin#NATURAL_TERRAIN} for space that was never excised —
     * naturally generated cave air, for instance — and {@link MiningOrigin#UNKNOWN} when the
     * removal is real but unattributable. Returning {@code Optional.empty()} means "no removal
     * information at all", which the analyser treats as unaccountably hidden only after
     * checking that the space is genuinely enclosed.
     */
    Optional<MiningOrigin> originAt(WorldId world, BlockPos pos);

    /**
     * Wall-clock time at which the block at {@code pos} was removed, in epoch milliseconds,
     * or {@code -1} when unknown.
     *
     * <p>This is what allows the analyser to distinguish "the player is standing in the tunnel
     * they dug ten minutes ago" (a legitimate re-entry) from "the player just carved this
     * opening in the last few seconds" (their approach excavation, which must not be counted as
     * pre-existing exposure).
     */
    long removalEpochMillis(WorldId world, BlockPos pos);
}
