package io.xrayac.core.domain;

import io.xrayac.core.geom.BlockPos;
import io.xrayac.core.geom.Vector3;
import java.time.Instant;

/**
 * A single fact observed about a player, in platform-neutral form.
 *
 * <p>This is the boundary type between the Paper adapter and the analytical core. The adapter
 * translates Bukkit events into these records on the server thread; everything downstream —
 * geometry, exposure analysis, statistics, evidence — operates only on them. Nothing here
 * references a Bukkit or Paper type, which is what allows the whole analytical engine to be
 * unit-tested without a running Minecraft server.
 *
 * <p>Modelled as a sealed hierarchy so that consumers which switch on observation kind are
 * checked exhaustively by the compiler: adding a new observation type becomes a compile error
 * at every site that must handle it, rather than a silently ignored case.
 *
 * <p>Every observation is immutable and self-contained (it carries its own player, world,
 * tick and timestamp) so it can be safely handed to a worker thread and persisted later
 * without cross-thread reads of mutable server state.
 */
public sealed interface Observation {

    PlayerRef player();

    WorldId world();

    /** Server tick counter. Monotonic within a session; used for ordering. */
    long tick();

    /** Wall-clock time of the observation, for evidence decay and human-readable timelines. */
    Instant timestamp();

    /**
     * A change of the player's position.
     *
     * @param from      position before the move
     * @param to        position after the move
     * @param teleported whether the move was a teleport (plugin, portal, ender pearl, command).
     *                   Teleports must be flagged because the resulting displacement is not a
     *                   trajectory and must never be fed to the geometry engine as one.
     */
    record Movement(
            PlayerRef player,
            WorldId world,
            long tick,
            Instant timestamp,
            Vector3 from,
            Vector3 to,
            boolean teleported) implements Observation {

        public Movement {
            if (from == null || to == null) {
                throw new IllegalArgumentException("movement requires both endpoints");
            }
            if (!from.isFinite() || !to.isFinite()) {
                throw new IllegalArgumentException("movement endpoints must be finite: " + from + " -> " + to);
            }
        }

        /** The displacement vector of this step. */
        public Vector3 delta() {
            return to.subtract(from);
        }

        /** Distance travelled in this step, in blocks. */
        public double distance() {
            return from.distanceTo(to);
        }

        /** Whether this step is usable as a genuine directed movement observation. */
        public boolean isMeaningfulStep() {
            return !teleported && distance() > 1e-6;
        }
    }

    /**
     * A change of the player's view direction.
     *
     * @param yawDegrees   yaw in degrees (Bukkit convention)
     * @param pitchDegrees pitch in degrees, negative looking up
     */
    record Orientation(
            PlayerRef player,
            WorldId world,
            long tick,
            Instant timestamp,
            double yawDegrees,
            double pitchDegrees) implements Observation {

        public Orientation {
            if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
                throw new IllegalArgumentException("orientation must be finite: "
                        + yawDegrees + ", " + pitchDegrees);
            }
        }

        /** The unit look vector implied by this orientation. */
        public Vector3 lookVector() {
            return Vector3.fromYawPitchDegrees(yawDegrees, pitchDegrees);
        }
    }

    /**
     * A block broken by the player.
     *
     * @param blockPos  the block that was removed
     * @param blockType a stable, namespaced material key (for example {@code minecraft:diamond_ore})
     * @param origin    attribution of who removed it, when known
     */
    record Mining(
            PlayerRef player,
            WorldId world,
            long tick,
            Instant timestamp,
            BlockPos blockPos,
            String blockType,
            MiningOrigin origin) implements Observation {

        public Mining {
            if (blockPos == null) {
                throw new IllegalArgumentException("mining observation requires a block position");
            }
            if (blockType == null || blockType.isBlank()) {
                throw new IllegalArgumentException("mining observation requires a block type");
            }
            if (origin == null) {
                throw new IllegalArgumentException("mining observation requires an origin attribution");
            }
        }
    }
}
