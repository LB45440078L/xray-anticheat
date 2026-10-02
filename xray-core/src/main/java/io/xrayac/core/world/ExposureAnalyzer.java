package io.xrayac.core.world;

import io.xrayac.core.domain.ExposureResult;
import io.xrayac.core.domain.ExposureState;
import io.xrayac.core.domain.MiningOrigin;
import io.xrayac.core.domain.PlayerRef;
import io.xrayac.core.domain.WorldId;
import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import io.xrayac.core.port.WorldView;
import java.time.Instant;
import java.util.Optional;

/**
 * Classifies how visible an ore block was to a player <b>at the moment the player encountered
 * it</b>.
 *
 * <h2>Why this is not "is the ore surrounded by air?"</h2>
 * The obvious implementation — check the six neighbours, count air, call it visible or hidden —
 * is wrong in both directions, and both errors are expensive:
 *
 * <ul>
 *   <li><b>False "hidden".</b> A player strip-mining through solid stone exposes hundreds of
 *       blocks that were genuinely enclosed before they dug. Classifying each as "hidden" would
 *       tar every legitimate strip miner, because the ore really was hidden right up to the
 *       instant the player's own pickaxe reached it. What matters is whether the ore was
 *       observable <i>before</i> the player's approach excavation, not whether it is
 *       surrounded at analysis time.</li>
 *   <li><b>False "visible".</b> An ore sitting in a natural cavern is visible, and a player who
 *       finds it has done nothing unusual. Treating "there is air next to it" as suspicious
 *       punishes cave explorers.</li>
 * </ul>
 *
 * <p>The analyser therefore separates the openings around an ore into:
 * <ul>
 *   <li><b>natural / unattributed openings</b> — the ore was genuinely observable, so its
 *       discovery is explained without invoking X-ray;</li>
 *   <li><b>the observing player's <i>recent</i> excavation</b> — openings the player carved in
 *       the seconds before reaching this block, which do <i>not</i> count as pre-existing
 *       visibility; this is what makes a strip-mined ore register as hidden;</li>
 *   <li><b>older excavation, by this player or another</b> — pre-existing tunnels. Discovery
 *       through them is explained by exploration, so the evidence is discounted rather than
 *       credited.</li>
 * </ul>
 *
 * <p>The result is a state, not a boolean, and every classification carries a rationale so that
 * a moderator can see how it was reached.
 *
 * <p><b>Threading.</b> Stateless and immutable; a single instance may be shared by all analysis
 * workers. It calls {@link WorldView}, which is server-thread-confined, so instances of this
 * class must be invoked on the server thread as part of observation collection.
 */
public final class ExposureAnalyzer {

    private final ExposurePolicy policy;

    public ExposureAnalyzer(ExposurePolicy policy) {
        this.policy = policy;
    }

    public ExposurePolicy policy() {
        return policy;
    }

    /**
     * Classifies the exposure of the ore at {@code orePos} with respect to {@code observer}.
     *
     * @param world           the dimension
     * @param orePos          the ore block
     * @param observer        the player whose encounter we are judging
     * @param observationTime wall-clock time of the encounter
     * @param view            world state access (server thread only)
     */
    public ExposureResult analyze(WorldId world,
                                  BlockPos orePos,
                                  PlayerRef observer,
                                  Instant observationTime,
                                  WorldView view) {

        if (!view.isLoaded(world, orePos)) {
            return ExposureResult.unknown(orePos, "the ore's own chunk is not loaded");
        }

        int openFaces = 0;
        int natural = 0;
        int unattributed = 0;
        int recentSelfExcavation = 0;
        int olderSelfExcavation = 0;
        int otherExcavation = 0;
        long youngestRemoval = -1L;
        boolean anyUnloaded = false;

        long windowStart = observationTime.toEpochMilli() - policy.recentExcavationWindowMillis();

        for (BlockPos offset : BlockPos.ORTHOGONAL_OFFSETS) {
            BlockPos neighbour = orePos.add(offset);
            if (!view.isLoaded(world, neighbour)) {
                anyUnloaded = true;
                continue;
            }
            BlockKind kind = view.kindAt(world, neighbour);
            if (!kind.transmitsVisibility()) {
                continue;
            }
            openFaces++;

            Optional<MiningOrigin> origin = view.originAt(world, neighbour);
            MiningOrigin resolved = origin.orElse(MiningOrigin.UNKNOWN);
            long removedAt = view.removalEpochMillis(world, neighbour);
            if (removedAt > 0 && (youngestRemoval < 0 || removedAt > youngestRemoval)) {
                youngestRemoval = removedAt;
            }

            switch (resolved) {
                case PLAYER_CREATED -> {
                    // Distinguish the player's own fresh approach excavation from a tunnel they
                    // dug earlier and have now returned to. Only the former is "not exposure".
                    if (removedAt > 0 && removedAt >= windowStart) {
                        recentSelfExcavation++;
                    } else {
                        olderSelfExcavation++;
                    }
                }
                case OTHER_PLAYER_CREATED -> otherExcavation++;
                case NATURAL_TERRAIN, ENVIRONMENTAL -> natural++;
                case UNKNOWN -> unattributed++;
            }
        }

        if (anyUnloaded) {
            // A missing neighbour could have been the opening that made this ore visible. We
            // cannot honestly claim it was hidden, so we refuse to classify.
            return ExposureResult.unknown(orePos,
                    "one or more neighbouring chunks are not loaded, so the block's surroundings are unknown");
        }

        double confidence = (policy.excavationAttributionRequired() && unattributed > 0) ? 0.6 : 0.95;
        long excavationAge = youngestRemoval > 0 ? observationTime.toEpochMilli() - youngestRemoval : -1L;

        // Openings that explain the discovery without invoking X-ray.
        int openlyVisible = natural + unattributed;

        if (openFaces == 0) {
            return classifyEnclosed(world, orePos, view, excavationAge, confidence);
        }

        if (openlyVisible > 0) {
            String rationale = String.format(
                    "%d of 6 faces open, of which %d natural and %d unattributed; a player could have "
                            + "seen this ore by ordinary exploration without any ore-vision assistance",
                    openFaces, natural, unattributed);
            return new ExposureResult(ExposureState.FULLY_EXPOSED, openFaces, natural > 0, false,
                    excavationAge, confidence, rationale);
        }

        if (otherExcavation > 0) {
            return new ExposureResult(ExposureState.CONDITIONALLY_EXPOSED, openFaces, false, true,
                    excavationAge, confidence, String.format(
                    "all %d open faces were mined by other players; the discoverer was following a "
                            + "tunnel somebody else created, so the discovery is explained without ore vision",
                    openFaces));
        }

        if (olderSelfExcavation > 0) {
            return new ExposureResult(ExposureState.PARTIALLY_EXPOSED, openFaces, false, true,
                    excavationAge, confidence, String.format(
                    "%d of %d open faces were mined by this player earlier (beyond the %.1f s approach window); "
                            + "the player re-entered ground they had previously worked",
                    olderSelfExcavation, openFaces, policy.recentExcavationWindowMillis() / 1000.0));
        }

        // Everything open here was cut by the observing player in the last few seconds. Before
        // that excavation the ore was fully enclosed: this is a genuinely hidden ore.
        return new ExposureResult(ExposureState.HIDDEN, openFaces, false, true, excavationAge, confidence,
                String.format(
                        "all %d open faces are this player's own excavation from within the last %.1f s; "
                                + "before that approach the ore was enclosed by untouched terrain",
                        openFaces, policy.recentExcavationWindowMillis() / 1000.0));
    }

    /**
     * Handles the fully enclosed case: either an unaccountably hidden ore, or — if a natural
     * cavity lies within the configured visibility radius with a clear line of sight — an ore
     * that a player in that cavity could have seen.
     */
    private ExposureResult classifyEnclosed(WorldId world,
                                            BlockPos orePos,
                                            WorldView view,
                                            long excavationAge,
                                            double confidence) {

        if (policy.diagonalVisibility() || policy.occupiedSpaceRadius() > 1) {
            Optional<BlockPos> visibleFrom = findNaturalVantagePoint(world, orePos, view);
            if (visibleFrom.isPresent()) {
                BlockPos vantage = visibleFrom.get();
                return new ExposureResult(ExposureState.PARTIALLY_EXPOSED, 0, true, false, excavationAge,
                        confidence * 0.85, String.format(
                        "enclosed on all six faces, but naturally generated space at %s has an unobstructed "
                                + "line of sight to the ore, so it could have been seen from there",
                        vantage));
            }
        }

        return new ExposureResult(ExposureState.HIDDEN, 0, false, false, excavationAge, confidence,
                "all six faces are bounded by opaque, unexcavated terrain; there is no opening from which "
                        + "this ore could have been observed before the player reached it");
    }

    /**
     * Searches the configured radius for naturally generated occupiable space with an
     * unobstructed line of sight to the ore.
     *
     * <p>The line-of-sight test walks the segment between the two block centres in small steps
     * and requires every intervening block to transmit visibility. This is a deliberately
     * conservative approximation of Minecraft's own raycast (it ignores per-block models and
     * partial transparency shapes), and it is used only to <i>downgrade</i> a HIDDEN verdict to
     * PARTIALLY_EXPOSED — never the reverse. An approximation that can only ever make the
     * system more lenient toward the player is the correct direction of error.
     */
    private Optional<BlockPos> findNaturalVantagePoint(WorldId world, BlockPos orePos, WorldView view) {
        int radius = policy.occupiedSpaceRadius();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = orePos.add(dx, dy, dz);
                    if (!view.isLoaded(world, candidate)) {
                        continue;
                    }
                    if (!view.kindAt(world, candidate).providesStandingRoom()) {
                        continue;
                    }
                    MiningOrigin origin = view.originAt(world, candidate).orElse(MiningOrigin.UNKNOWN);
                    if (origin == MiningOrigin.PLAYER_CREATED || origin == MiningOrigin.OTHER_PLAYER_CREATED) {
                        // A vantage point that only exists because somebody dug it does not
                        // count as natural exposure.
                        continue;
                    }
                    if (hasLineOfSight(world, orePos, candidate, view)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Samples the segment between two block centres; true when no sampled block other than the
     * two endpoints is opaque.
     *
     * <p>The endpoints are excluded because the origin is the ore itself (an opaque block by
     * definition, and sampling it would make every sight line fail) and the target is the
     * vantage point, which is known to be occupiable space.
     */
    private boolean hasLineOfSight(WorldId world, BlockPos from, BlockPos to, WorldView view) {
        Vector3 a = from.center();
        Vector3 b = to.center();
        Vector3 delta = b.subtract(a);
        double distance = delta.length();
        if (distance < 1e-6) {
            return true;
        }
        int steps = (int) Math.ceil(distance / 0.2);
        BlockPos lastBlock = null;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            BlockPos sample = BlockPos.containing(a.add(delta.scale(t)));
            if (sample.equals(lastBlock)) {
                continue;
            }
            lastBlock = sample;
            if (sample.equals(from) || sample.equals(to)) {
                continue;
            }
            if (!view.isLoaded(world, sample)) {
                // Unknown intervening space: refuse to claim a sight line.
                return false;
            }
            if (!view.kindAt(world, sample).transmitsVisibility()) {
                return false;
            }
        }
        return true;
    }
}
