package io.xrayac.core.geom;

/**
 * Immutable three-dimensional vector of {@code double} components.
 *
 * <p>This is the fundamental numeric primitive of the analytical core. World positions,
 * look directions, movement deltas and ore offsets are all represented here so that the
 * statistical engine never has to reason about a platform-specific location type.
 *
 * <p>Orientation convention (matching Minecraft's coordinate system, but expressed
 * generically): +X east, +Y up, +Z south. Yaw is measured clockwise from -Z and pitch is
 * positive downwards. All angular quantities in this package are returned in <b>radians</b>
 * unless a method name explicitly states otherwise, so that trig identities hold without
 * unit conversion in intermediate steps.
 *
 * <p>Instances are immutable, hence inherently thread-safe and safe to share across the
 * asynchronous analysis workers.
 */
public record Vector3(double x, double y, double z) {

    /** The zero vector. */
    public static final Vector3 ZERO = new Vector3(0.0, 0.0, 0.0);

    /** Unit vector pointing up. */
    public static final Vector3 UP = new Vector3(0.0, 1.0, 0.0);

    public static Vector3 of(double x, double y, double z) {
        return new Vector3(x, y, z);
    }

    /**
     * Builds a direction vector from Minecraft-style yaw/pitch, in degrees.
     *
     * <p>This is the canonical conversion used when translating an
     * {@code OrientationObservation} into a unit look vector. Yaw 0 faces +Z under the
     * Bukkit convention where yaw increases clockwise; pitch is clamped to [-90, 90]
     * because the game itself clamps it and a non-finite direction would poison every
     * downstream angle computation.
     */
    public static Vector3 fromYawPitchDegrees(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(Math.clamp(pitchDegrees, -90.0, 90.0));
        double cosPitch = Math.cos(pitch);
        return new Vector3(
                -Math.sin(yaw) * cosPitch,
                -Math.sin(pitch),
                Math.cos(yaw) * cosPitch);
    }

    public Vector3 add(Vector3 o) {
        return new Vector3(x + o.x, y + o.y, z + o.z);
    }

    public Vector3 subtract(Vector3 o) {
        return new Vector3(x - o.x, y - o.y, z - o.z);
    }

    public Vector3 scale(double s) {
        return new Vector3(x * s, y * s, z * s);
    }

    public double dot(Vector3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vector3 cross(Vector3 o) {
        return new Vector3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    /**
     * Returns a unit vector in the same direction.
     *
     * @throws IllegalArgumentException when this vector is (numerically) zero, because a
     *         normalized zero vector is undefined and silently returning ZERO hides bugs
     * @throws IllegalStateException when a component is non-finite
     */
    public Vector3 normalize() {
        double len = length();
        if (!Double.isFinite(len)) {
            throw new IllegalStateException("cannot normalize a non-finite vector: " + this);
        }
        if (len < 1e-12) {
            throw new IllegalArgumentException("cannot normalize a zero-length vector");
        }
        return new Vector3(x / len, y / len, z / len);
    }

    /** Euclidean distance to another point/vector. */
    public double distanceTo(Vector3 o) {
        return subtract(o).length();
    }

    /**
     * Angle between this and another vector, in radians, in {@code [0, pi]}.
     *
     * <p>Computed via {@code atan2(|a x b|, a . b)} rather than {@code acos(a.b / |a||b|)}
     * because {@code acos} is catastrophically ill-conditioned for near-parallel vectors:
     * floating-point error in the dot product can push the ratio slightly beyond 1 and
     * produce {@code NaN}, or produce large relative error for very small angles. The
     * {@code atan2} form is stable across the whole range, which matters because
     * near-parallel alignment between a movement vector and an ore direction is exactly
     * the signal we care about most.
     */
    public double angleTo(Vector3 o) {
        double cross = cross(o).length();
        double dot = dot(o);
        if (cross == 0.0 && dot == 0.0) {
            // One or both vectors are zero length: the angle is undefined.
            throw new IllegalArgumentException("angle is undefined for a zero vector");
        }
        return Math.atan2(cross, dot);
    }

    /**
     * Angle between this vector and another, in degrees.
     */
    public double angleToDegrees(Vector3 o) {
        return Math.toDegrees(angleTo(o));
    }

    /**
     * Scalar projection of this vector onto {@code axis} (which is normalized internally).
     * Returns 0 for a zero axis rather than throwing, because callers frequently probe
     * with a possibly-degenerate axis and treat "no projection" as meaningful.
     */
    public double projectOnto(Vector3 axis) {
        double axisLen = axis.length();
        if (axisLen < 1e-12) {
            return 0.0;
        }
        return dot(axis) / axisLen;
    }

    /** Vector projection of this vector onto {@code axis}. */
    public Vector3 projectVectorOnto(Vector3 axis) {
        double axisLenSq = axis.lengthSquared();
        if (axisLenSq < 1e-24) {
            return ZERO;
        }
        return axis.scale(dot(axis) / axisLenSq);
    }

    /** Component of this vector orthogonal to {@code axis}. */
    public Vector3 rejectFrom(Vector3 axis) {
        return subtract(projectVectorOnto(axis));
    }

    /**
     * Perpendicular distance from this point to the infinite line through
     * {@code lineOrigin} with unit direction {@code lineDirection}.
     *
     * <p>Used to measure how close a hidden ore sits to a player's trajectory: a targeting
     * player's path tends to pass within a short perpendicular distance of the ore while
     * never looking directly at it.
     */
    public double distanceToLine(Vector3 lineOrigin, Vector3 lineDirection) {
        Vector3 dir = lineDirection.normalize();
        Vector3 toPoint = subtract(lineOrigin);
        return rejectFrom(dir).length();
    }

    /**
     * Shortest distance from this point to the finite segment {@code [a, b]}.
     * Falls back to endpoint distance when the perpendicular foot lies outside the segment.
     */
    public double distanceToSegment(Vector3 a, Vector3 b) {
        Vector3 ab = b.subtract(a);
        double abLenSq = ab.lengthSquared();
        if (abLenSq < 1e-24) {
            return distanceTo(a);
        }
        double t = Math.clamp(subtract(a).dot(ab) / abLenSq, 0.0, 1.0);
        return distanceTo(a.add(ab.scale(t)));
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** Horizontal (X/Z) magnitude, ignoring vertical movement. */
    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    /** Returns the purely horizontal projection of this vector. */
    public Vector3 horizontal() {
        return new Vector3(x, 0.0, z);
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
