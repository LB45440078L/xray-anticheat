package io.xrayac.core.geom;

import java.util.List;
import java.util.Objects;

/**
 * Principal component analysis for small 3D point clouds, via the eigendecomposition of the
 * symmetric 3x3 covariance matrix.
 *
 * <p>Rationale: a mined tunnel is naturally described as a point cloud (the mined block
 * centres, or a polyline of path vertices). The eigenstructure of its covariance matrix
 * yields, in one shot, the dominant direction of travel, how "line-like" versus "plane-like"
 * versus "blob-like" the cloud is, and how much the tunnel deviates from its own principal
 * axis. Those are exactly the geometric quantities the tunnel model consumes.
 *
 * <p>The decomposition uses the classical cyclic Jacobi rotation method rather than a
 * general-purpose linear algebra dependency. For a fixed 3x3 symmetric matrix Jacobi
 * converges quadratically and terminates in a handful of sweeps; it needs no pivoting
 * heuristics, no external library, and is numerically robust because it only ever applies
 * orthogonal similarity transforms (which preserve symmetry and cannot amplify error).
 * Introducing a full BLAS/LAPACK dependency for one 3x3 routine would be unjustified.
 *
 * <p>Eigenvalues are returned in <b>descending</b> order with their eigenvectors, so
 * {@code axis(0)} is always the dominant direction. Eigenvectors are only defined up to
 * sign; callers must never assume a particular orientation of an axis. Where the sign
 * carries meaning (a direction of travel, say), the caller re-derives it by projecting the
 * measured displacement onto the axis.
 */
public final class PrincipalAxes {

    private final Vector3 centroid;
    private final double[] eigenvalues;
    private final Vector3[] eigenvectors;

    private PrincipalAxes(Vector3 centroid, double[] eigenvalues, Vector3[] eigenvectors) {
        this.centroid = centroid;
        this.eigenvalues = eigenvalues;
        this.eigenvectors = eigenvectors;
    }

    /** The mean of the analysed points. */
    public Vector3 centroid() {
        return centroid;
    }

    /** Descending eigenvalues of the covariance matrix (variance along each axis). */
    public double eigenvalue(int i) {
        return eigenvalues[i];
    }

    /** Eigenvector for {@link #eigenvalue(int)}; index 0 is the dominant axis. */
    public Vector3 eigenvector(int i) {
        return eigenvectors[i];
    }

    /** The dominant direction of the point cloud (unit length, sign arbitrary). */
    public Vector3 dominantAxis() {
        return eigenvectors[0];
    }

    /** Total variance (trace of the covariance matrix). */
    public double totalVariance() {
        return eigenvalues[0] + eigenvalues[1] + eigenvalues[2];
    }

    /**
     * Fraction of total variance explained by the dominant axis, in {@code [0, 1]}.
     *
     * <p>A value near 1 means the cloud is essentially one-dimensional (a straight tunnel);
     * a value near 1/3 means it is isotropic (random scatter or a blob).
     */
    public double dominantExplainedRatio() {
        double total = totalVariance();
        if (total < 1e-18) {
            return 0.0;
        }
        return eigenvalues[0] / total;
    }

    /**
     * Linearity feature {@code (l1 - l2) / l1}, in {@code [0, 1]}.
     *
     * <p>Standard dimensionality descriptor for point clouds. Near 1 for a clean line,
     * near 0 when variance is spread across at least two axes.
     */
    public double linearity() {
        if (eigenvalues[0] < 1e-18) {
            return 0.0;
        }
        return (eigenvalues[0] - eigenvalues[1]) / eigenvalues[0];
    }

    /**
     * Planarity feature {@code (l2 - l3) / l1}, in {@code [0, 1]}.
     *
     * <p>High when the cloud lies mostly in a plane — for example a wide, flat branch-mine
     * gallery, or the outer face of a cave wall.
     */
    public double planarity() {
        if (eigenvalues[0] < 1e-18) {
            return 0.0;
        }
        return (eigenvalues[1] - eigenvalues[2]) / eigenvalues[0];
    }

    /**
     * Mean perpendicular (off-axis) deviation of the points from the dominant axis, in
     * blocks. This is the RMS radius of the cloud about its own principal axis and is the
     * natural measure of how "straight" a tunnel is: a perfect 1x1 corridor has a small
     * constant radius, a meandering tunnel a much larger one.
     */
    public double rmsOffAxisDeviation() {
        return Math.sqrt(Math.max(0.0, eigenvalues[1] + eigenvalues[2]));
    }

    /**
     * Computes principal axes for the given points.
     *
     * @throws IllegalArgumentException if fewer than two points are supplied, or all points
     *         are coincident (the covariance matrix is then exactly zero and the axes are
     *         arbitrary — reporting a direction would be a fabrication)
     */
    public static PrincipalAxes of(List<Vector3> points) {
        Objects.requireNonNull(points, "points");
        int n = points.size();
        if (n < 2) {
            throw new IllegalArgumentException("PCA requires at least 2 points, got " + n);
        }

        double sx = 0;
        double sy = 0;
        double sz = 0;
        for (Vector3 p : points) {
            sx += p.x();
            sy += p.y();
            sz += p.z();
        }
        Vector3 centroid = new Vector3(sx / n, sy / n, sz / n);

        // Covariance matrix (biased/MLE form: divide by n). The choice affects only the
        // absolute scale of the eigenvalues, not the eigenvectors or any ratio-based
        // feature derived from them, so it does not influence our decisions.
        double cxx = 0;
        double cyy = 0;
        double czz = 0;
        double cxy = 0;
        double cxz = 0;
        double cyz = 0;
        for (Vector3 p : points) {
            double dx = p.x() - centroid.x();
            double dy = p.y() - centroid.y();
            double dz = p.z() - centroid.z();
            cxx += dx * dx;
            cyy += dy * dy;
            czz += dz * dz;
            cxy += dx * dy;
            cxz += dx * dz;
            cyz += dy * dz;
        }
        cxx /= n;
        cyy /= n;
        czz /= n;
        cxy /= n;
        cxz /= n;
        cyz /= n;

        double total = cxx + cyy + czz;
        if (total < 1e-18) {
            throw new IllegalArgumentException("all points are coincident; axes are undefined");
        }

        double[][] a = {
                {cxx, cxy, cxz},
                {cxy, cyy, cyz},
                {cxz, cyz, czz}};
        double[][] v = identity();

        jacobiEigen(a, v);

        // Sort eigenpairs by descending eigenvalue (simple insertion sort for 3 elements).
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (i, j) -> Double.compare(a[j][j], a[i][i]));

        double[] eigenvalues = new double[3];
        Vector3[] eigenvectors = new Vector3[3];
        for (int k = 0; k < 3; k++) {
            int idx = order[k];
            eigenvalues[k] = Math.max(0.0, a[idx][idx]);
            eigenvectors[k] = new Vector3(v[idx][0], v[idx][1], v[idx][2]).normalize();
        }
        return new PrincipalAxes(centroid, eigenvalues, eigenvectors);
    }

    /**
     * Cyclic Jacobi eigen-decomposition of a real symmetric matrix. On return {@code a} holds
     * the eigenvalues on its diagonal and {@code v} the accumulated (orthogonal) eigenvector
     * matrix, whose columns are the eigenvectors.
     */
    private static void jacobiEigen(double[][] a, double[][] v) {
        final int maxSweeps = 50;
        final double epsilon = 1e-12;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            double offDiagonal =
                    Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (offDiagonal < epsilon) {
                return;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                    // The smaller-magnitude root is chosen for numerical stability; this is
                    // the standard trick that avoids cancellation when theta is large.
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    if (theta == 0.0) {
                        t = 1.0;
                    }
                    double c = 1.0 / Math.sqrt(t * t + 1.0);
                    double s = t * c;

                    double app = a[p][p];
                    double aqq = a[q][q];
                    double apq = a[p][q];
                    a[p][p] = app - t * apq;
                    a[q][q] = aqq + t * apq;
                    a[p][q] = 0.0;
                    a[q][p] = 0.0;

                    for (int k = 0; k < 3; k++) {
                        if (k != p && k != q) {
                            double akp = a[k][p];
                            double akq = a[k][q];
                            a[k][p] = c * akp - s * akq;
                            a[p][k] = a[k][p];
                            a[k][q] = s * akp + c * akq;
                            a[q][k] = a[k][q];
                        }
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
    }

    private static double[][] identity() {
        double[][] m = new double[3][3];
        m[0][0] = 1.0;
        m[1][1] = 1.0;
        m[2][2] = 1.0;
        return m;
    }
}
