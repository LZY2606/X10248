package polwork.domain;

import java.util.List;

/**
 * Measurement noise covariance over the flattened intensities. v1 is diagonal
 * (Poisson+dark); v2 introduces known off-diagonal (correlated detector
 * columns). Unit: counts^2.
 */
public final class CovarianceSpec {
    public final String versionId;
    public final int n;
    public final double[][] matrix;
    public final List<String> ordering; // how the flattened rows map to (g,a)
    public final String unit;
    public final String note;

    public CovarianceSpec(String versionId, int n, double[][] matrix,
                          List<String> ordering, String unit, String note) {
        if (matrix.length != n) throw new IllegalArgumentException("covariance size mismatch");
        this.versionId = versionId;
        this.n = n;
        this.matrix = matrix;
        this.ordering = ordering;
        this.unit = unit;
        this.note = note;
    }
}
