package io.xrayac.paper.adapter;

import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.port.WorldView;
import io.xrayac.core.world.BlockKind;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The Paper implementation of {@link WorldView}: the adapter that lets the analytical core read
 * world state without ever holding a Bukkit object.
 *
 * <h2>Threading contract, in the concrete</h2>
 * Every method here touches the live server, so every method must be called on the Minecraft server
 * thread. It is not merely a convention: {@link #isLoaded} deliberately checks
 * {@link World#isChunkLoaded} rather than reading the block directly, because a block read in an
 * unloaded chunk would make the server synchronously generate or load that chunk — a latency spike
 * proportional to world generation, triggered by a block break. Returning {@code false} there lets
 * the analyser degrade to UNKNOWN, which is the honest answer and the cheap one.
 *
 * <h2>The provenance assumption, stated openly</h2>
 * {@link #originAt} reports air or fluid with no ledger record as
 * {@link MiningOrigin#NATURAL_TERRAIN}. That is an assumption, not an observation: it means "no
 * tracked player is recorded as having removed this, so it was presumably placed there by world
 * generation". It is wrong when the ledger has pruned the relevant entry, or when the block was
 * broken before the plugin was installed. In both cases the error makes the analyser <i>more</i>
 * generous — an old player-dug tunnel may be credited as a natural cavity — which is the correct
 * direction for a system whose most expensive mistake is accusing someone wrongly. Administrators
 * who would rather see uncertainty than an assumption can set
 * {@code analysis.exposure.excavation-attribution-required: true}, which reduces the confidence of
 * any classification resting on an unattributed opening.
 */
public final class BukkitWorldView implements WorldView {

    private final ExcavationLedger ledger;
    private final MaterialClassifier classifier;

    public BukkitWorldView(ExcavationLedger ledger, MaterialClassifier classifier) {
        this.ledger = ledger;
        this.classifier = classifier;
    }

    @Override
    public boolean isLoaded(WorldId world, BlockPos pos) {
        World bukkitWorld = Bukkit.getWorld(world.key());
        if (bukkitWorld == null) {
            return false;
        }
        // Chunk coordinates, not block coordinates.
        return bukkitWorld.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    @Override
    public BlockKind kindAt(WorldId world, BlockPos pos) {
        World bukkitWorld = Bukkit.getWorld(world.key());
        if (bukkitWorld == null || !isLoaded(world, pos)) {
            return BlockKind.UNKNOWN;
        }
        // getBlockAt is safe here only because isLoaded has already been checked.
        Block block = bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z());
        return classifier.classify(block.getType());
    }

    @Override
    public String blockKeyAt(WorldId world, BlockPos pos) {
        World bukkitWorld = Bukkit.getWorld(world.key());
        if (bukkitWorld == null || !isLoaded(world, pos)) {
            return null;
        }
        return classifier.blockKey(bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()).getType());
    }

    @Override
    public Optional<MiningOrigin> originAt(WorldId world, BlockPos pos) {
        if (!isLoaded(world, pos)) {
            return Optional.empty();
        }

        // A recorded removal always wins: it is a fact, whereas everything below is inference.
        Optional<MiningOrigin> recorded = ledger.originAt(world, pos);
        if (recorded.isPresent()) {
            return recorded;
        }

        BlockKind kind = kindAt(world, pos);
        if (kind == BlockKind.UNKNOWN) {
            return Optional.empty();
        }
        if (kind.transmitsVisibility()) {
            // Open space with no removal on record: treated as world-generated. See the class
            // comment for why this assumption is made and which way its error points.
            return Optional.of(MiningOrigin.NATURAL_TERRAIN);
        }
        // A solid block was never removed, so it has no origin. Returning empty (rather than a
        // default) is what lets the caller distinguish "not removed" from "removed by someone".
        return Optional.empty();
    }

    @Override
    public long removalEpochMillis(WorldId world, BlockPos pos) {
        return ledger.removedAtEpochMillis(world, pos);
    }
}
