package io.xrayac.core.analysis;

import io.xrayac.core.geom.PrincipalAxes;
import io.xrayac.core.geom.Vector3;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Three-dimensional geometric analysis of a player's path, and of how that path relates to a
 * specific ore.
 *
 * <h2>Why the analysis is three-dimensional and direction-aware</h2>
 * Mining in Minecraft is not a planar activity: a "straight tunnel" that is straight in the X/Z
 * plane may still drift freely in Y, and the single most diagnostic axis for ore-seeking
 * behaviour is the vertical one, because ores occupy narrow Y bands. Reducing a trajectory to
 * two dimensions, or to scalar summaries such as "blocks mined per hour", discards exactly the
 * structure that distinguishes a player following a seam downwards from one walking a level
 * corridor. Every quantity here is therefore computed on {@link Vector3}.
 *
 * <h2>What the geometry can and cannot tell us</h2>
 * None of these features is evidence of cheating on its own. A perfectly straight tunnel is what
 * a strip-miner produces, and a meandering one is what a cave explorer produces. They are
 * recorded because they modulate <i>other</i> evidence — a suspicious discovery rate achieved
 * along a path that is also suspiciously efficient is more informative than the same rate achieved
 * while wandering — and because a moderator reading an evidence report needs the geometry to
 * interpret the numbers.
 */
public final class TrajectoryAnalysis {

    private TrajectoryAnalysis() {
    }

    /**
     * A point on the player's path: where they were and when.
     */
    public record PathPoint(Vector3 position, Instant time, Vector3 lookDirection) {

        public PathPoint {
            if (position == null || time == null) {
                throw new IllegalArgumentException("a path point requires a position and a time");
            }
        }

        public static PathPoint of(Vector3 position, Instant time) {
            return new PathPoint(position, time, null);
        }
    }

    /**
     * Scalar shape features of a path.
     *
     * @param pointCount          number of path points analysed
     * @param pathLength          total traversed distance, in blocks
     * @param netDisplacement     straight-line distance from first to last point, in blocks
     * @param pathEfficiency      {@code netDisplacement / pathLength}, in {@code [0, 1]}; near 1
     *                            means the player travelled in a near-straight line, near 0 means
     *                            they wandered or backtracked. This is a measure of how directly
     *                            the player moved, NOT of honesty: a strip-miner scores high and
     *                            so does an ore-seeker.
     * @param straightness        dominant PCA eigenvalue share, in {@code [0, 1]}
     * @param totalTurningDegrees sum of absolute heading changes between successive segments
     * @param meanTurningDegrees  mean absolute heading change per segment
     * @param turnDensity         turns (heading changes above the configured threshold) per 100
     *                            blocks of path, a scale-free measure of how much the player
     *                            changes direction
     * @param verticalShare       fraction of path length that is vertical movement
     * @param verticalDrift       signed net Y displacement, in blocks (negative means descending)
     * @param dominantAxis        principal direction of the path (unit length, sign arbitrary)
     * @param maxDeviation        RMS off-axis deviation, in blocks
     */
    public record Geometry(
            int pointCount,
            double pathLength,
            double netDisplacement,
            double pathEfficiency,
            double straightness,
            double totalTurningDegrees,
            double meanTurningDegrees,
            double turnDensity,
            double verticalShare,
            double verticalDrift,
            Vector3 dominantAxis,
            double maxDeviation) {

        /** A geometry that carries no information because the path was too short. */
        public static Geometry empty() {
            return new Geometry(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Vector3.UP, 0);
        }

        public boolean hasData() {
            return pointCount >= 2 && pathLength > 1e-6;
        }
    }

    /** A heading change at or above this many degrees is counted as a "turn". */
    private static final double TURN_THRESHOLD_DEGREES = 30.0;

    /**
     * Computes path geometry. Requires at least two points; returns {@link Geometry#empty()} for
     * anything shorter, because reporting a "straightness" for a single point would be a
     * fabrication.
     */
    public static Geometry geometry(List<PathPoint> path) {
        if (path == null || path.size() < 2) {
            return Geometry.empty();
        }

        List<Vector3> points = new ArrayList<>(path.size());
        for (PathPoint p : path) {
            points.add(p.position());
        }

        double pathLength = 0.0;
        double verticalLength = 0.0;
        double totalTurning = 0.0;
        int turnCount = 0;

        Vector3 previousDirection = null;
        for (int i = 1; i < points.size(); i++) {
            Vector3 segment = points.get(i).subtract(points.get(i - 1));
            double segmentLength = segment.length();
            if (segmentLength < 1e-9) {
                continue;
            }
            pathLength += segmentLength;
            verticalLength += Math.abs(segment.y());

            Vector3 direction = segment.scale(1.0 / segmentLength);
            if (previousDirection != null) {
                double turn = Math.toDegrees(previousDirection.angleTo(direction));
                totalTurning += turn;
                if (turn >= TURN_THRESHOLD_DEGREES) {
                    turnCount++;
                }
            }
            previousDirection = direction;
        }

        if (pathLength < 1e-9) {
            return Geometry.empty();
        }

        Vector3 first = points.getFirst();
        Vector3 last = points.getLast();
        double netDisplacement = first.distanceTo(last);

        PrincipalAxes axes;
        try {
            axes = PrincipalAxes.of(points);
        } catch (IllegalArgumentException e) {
            // All points coincide; there is no shape to describe.
            return Geometry.empty();
        }

        double straightness = axes.dominantExplainedRatio();
        double verticalDrift = last.y() - first.y();
        double meanTurning = turnCount == 0 ? 0.0 : totalTurning / Math.max(1, points.size() - 2);
        double turnDensity = turnCount / (pathLength / 100.0);

        return new Geometry(
                points.size(),
                pathLength,
                netDisplacement,
                Math.clamp(netDisplacement / pathLength, 0.0, 1.0),
                straightness,
                totalTurning,
                meanTurning,
                turnDensity,
                Math.clamp(verticalLength / pathLength, 0.0, 1.0),
                verticalDrift,
                axes.dominantAxis(),
                axes.rmsOffAxisDeviation());
    }

    /**
     * How a player's path approached a specific target (usually a hidden ore).
     *
     * @param hadData                     whether enough path history existed before the target was
     *                                    reached to measure an approach at all
     * @param approachStartDistance       distance at which the player began the approach
     * @param movementAlignmentDegrees    angle between the player's movement direction and the
     *                                    direction to the target, measured at the approach start;
     *                                    small means they were heading almost straight at it
     * @param lookAlignmentDegrees        angle between the player's view direction and the
     *                                    direction to the target at the approach start, or NaN
     *                                    when orientation data was unavailable
     * @param travelledTowardsTarget      net distance the player closed on the target during the
     *                                    approach (positive means closing in)
     */
    public record Approach(
            boolean hadData,
            double approachStartDistance,
            double movementAlignmentDegrees,
            double lookAlignmentDegrees,
            double travelledTowardsTarget) {

        public static Approach noData() {
            return new Approach(false, Double.NaN, Double.NaN, Double.NaN, 0.0);
        }
    }

    /**
     * Measures how the player's path related to {@code target} before they reached it.
     *
     * <p>The measurement is taken at a fixed <b>lookback distance</b> rather than at the moment
     * of arrival. At arrival, every player is necessarily standing next to the ore and pointing
     * at it, so the angle carries no information; the interesting question is whether the player's
     * heading already pointed at the ore while it was still buried and out of sight.
     *
     * @param path             the player's path, oldest first, ending at the discovery
     * @param target           the ore's block centre
     * @param lookbackDistance how far from the target to sample the approach heading
     */
    public static Approach approach(List<PathPoint> path, Vector3 target, double lookbackDistance) {
        if (path == null || path.size() < 2 || lookbackDistance <= 0) {
            return Approach.noData();
        }

        // Walk backwards from the discovery to find the last point that was still at least
        // `lookbackDistance` away from the target: that is where the approach "began" from the
        // perspective of this measurement.
        int approachIndex = -1;
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).position().distanceTo(target) >= lookbackDistance) {
                approachIndex = i;
                break;
            }
        }
        if (approachIndex < 1) {
            return Approach.noData();
        }

        PathPoint approachPoint = path.get(approachIndex);
        PathPoint previous = path.get(approachIndex - 1);

        Vector3 movement = approachPoint.position().subtract(previous.position());
        if (movement.length() < 1e-9) {
            return Approach.noData();
        }
        Vector3 toTarget = target.subtract(approachPoint.position());
        if (toTarget.length() < 1e-9) {
            return Approach.noData();
        }

        double movementAlignment = Math.toDegrees(movement.angleTo(toTarget));
        double lookAlignment = Double.NaN;
        if (approachPoint.lookDirection() != null && approachPoint.lookDirection().length() > 1e-9) {
            lookAlignment = Math.toDegrees(approachPoint.lookDirection().angleTo(toTarget));
        }

        double travelledTowards = previous.position().distanceTo(target)
                - approachPoint.position().distanceTo(target);

        return new Approach(true,
                approachPoint.position().distanceTo(target),
                movementAlignment,
                lookAlignment,
                travelledTowards);
    }

    /** Convenience: the approach metrics wrapped in an Optional for callers that branch on it. */
    public static Optional<Approach> approachIfPresent(List<PathPoint> path, Vector3 target,
                                                       double lookbackDistance) {
        Approach approach = approach(path, target, lookbackDistance);
        return approach.hadData() ? Optional.of(approach) : Optional.empty();
    }
}
