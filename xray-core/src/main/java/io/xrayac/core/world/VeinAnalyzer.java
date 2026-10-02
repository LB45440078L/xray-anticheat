package io.xrayac.core.world;

import io.xrayac.core.domain.ExposureResult;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.VeinObservation;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import io.xrayac.core.port.OreCatalog;
import io.xrayac.core.port.WorldView;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reconstructs the ore vein containing a discovery and classifies the exposure of each of its
 * blocks.
 *
 * <h2>Connectivity choice</h2>
 * Blocks are considered connected on the 26-neighbourhood (faces, edges and corners). Minecraft
 * ore blobs are generated as roughly ellipsoidal clusters, so their blocks frequently touch only
 * diagonally; using 6-connectivity would split a single vein into several fragments and inflate
 * the number of "discoveries", which is precisely the pseudo-replication error the vein model
 * exists to prevent. 26-connectivity can in principle merge two veins that happen to touch at a
 * corner, which is the conservative direction: it makes the sample smaller and the evidence
 * weaker, never stronger.
 *
 * <h2>Cost</h2>
 * The search is bounded by {@code maxVeinSize}. Real ore veins are a handful of blocks
 * (diamond veins are typically 1–8, ancient debris 1–3), so the bound is generous in practice;
 * it exists so that a pathological or plugin-generated blob cannot turn one block break into an
 * unbounded world scan on the server thread.
 */
public final class VeinAnalyzer {

    private final OreCatalog catalog;
    private final ExposureAnalyzer exposureAnalyzer;
    private final int maxVeinSize;

    public VeinAnalyzer(OreCatalog catalog, ExposureAnalyzer exposureAnalyzer, int maxVeinSize) {
        this.catalog = catalog;
        this.exposureAnalyzer = exposureAnalyzer;
        if (maxVeinSize < 1 || maxVeinSize > 1024) {
            throw new IllegalArgumentException("maxVeinSize must lie in [1, 1024], got " + maxVeinSize);
        }
        this.maxVeinSize = maxVeinSize;
    }

    /**
     * Reconstructs and classifies the vein containing {@code seed}.
     *
     * @throws IllegalArgumentException when the seed block is not a configured ore
     */
    public VeinObservation analyze(WorldId world,
                                   BlockPos seed,
                                   String seedBlockKey,
                                   PlayerRef observer,
                                   Instant observationTime,
                                   WorldView view) {

        String oreId = catalog.oreIdFor(seedBlockKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "block " + seedBlockKey + " is not a configured ore"));

        Set<BlockPos> veinBlocks = new HashSet<>();
        Set<BlockPos> exposed = new HashSet<>();
        Set<BlockPos> hidden = new HashSet<>();
        // Every classified block's state is retained so the vein can be judged as a whole. The
        // exposed/hidden sets alone are not enough: they cannot distinguish "all blocks hidden" from
        // "most blocks hidden but one in the open", which is the distinction that decides whether a
        // player could have found this vein honestly.
        List<ExposureState> observedStates = new ArrayList<>();
        List<Vector3> centres = new ArrayList<>();

        Deque<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.add(seed);
        visited.add(seed);
        boolean truncated = false;

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (!view.isLoaded(world, current)) {
                // An unloaded block cannot be classified; skip it rather than assuming it is ore.
                continue;
            }
            veinBlocks.add(current);
            centres.add(current.center());

            ExposureResult exposure = exposureAnalyzer.analyze(world, current, observer, observationTime, view);
            observedStates.add(exposure.state());
            switch (exposure.state()) {
                case FULLY_EXPOSED, PARTIALLY_EXPOSED, CONDITIONALLY_EXPOSED -> exposed.add(current);
                case HIDDEN -> hidden.add(current);
                case UNKNOWN -> {
                    // Deliberately neither: unknown blocks must not be counted as hidden.
                }
            }

            if (veinBlocks.size() >= maxVeinSize) {
                truncated = !frontier.isEmpty() || hasMoreOreNeighbours(world, current, oreId, view, visited);
                break;
            }

            for (BlockPos offset : BlockPos.NEIGHBOURHOOD_26) {
                BlockPos neighbour = current.add(offset);
                if (visited.contains(neighbour) || !view.isLoaded(world, neighbour)) {
                    continue;
                }
                String key = view.blockKeyAt(world, neighbour);
                if (key != null && catalog.oreIdFor(key).filter(oreId::equals).isPresent()) {
                    visited.add(neighbour);
                    frontier.add(neighbour);
                } else {
                    visited.add(neighbour);
                }
            }
        }

        VeinObservation.VeinShape shape = VeinObservation.VeinShape.from(centres);

        // The vein is judged by its most visible member. See ExposureState.mostVisible for why the
        // block the player happened to break first must not decide the verdict.
        ExposureState aggregateExposure = ExposureState.mostVisible(observedStates);

        return new VeinObservation(oreId, seed, veinBlocks, exposed, hidden,
                aggregateExposure, shape, truncated);
    }

    private boolean hasMoreOreNeighbours(WorldId world, BlockPos current, String oreId,
                                         WorldView view, Set<BlockPos> visited) {
        for (BlockPos offset : BlockPos.NEIGHBOURHOOD_26) {
            BlockPos neighbour = current.add(offset);
            if (visited.contains(neighbour) || !view.isLoaded(world, neighbour)) {
                continue;
            }
            String key = view.blockKeyAt(world, neighbour);
            if (key != null && catalog.oreIdFor(key).filter(oreId::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> oreIdOf(String blockKey) {
        return catalog.oreIdFor(blockKey);
    }
}
