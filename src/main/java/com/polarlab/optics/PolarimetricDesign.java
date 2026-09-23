package com.polarlab.optics;

import com.polarlab.math.Linalg;
import com.polarlab.math.Mat;

import java.util.List;

/**
 * Builds the measurement (design) matrix W for a set of generator/analyzer pairs.
 *
 * Measured (dark-corrected) intensity for pair (g, a) is
 *   I_g,a = a_row * M_sample * s_gen
 * which is linear in the 16 Mueller components m: I = W m,
 * with W rows = kron(s_gen^T, a_row) in the active basis convention.
 */
public final class PolarimetricDesign {

    public final List<OpticalState> generators;
    public final List<OpticalState> analyzers;
    public final List<int[]> pairs;
    public final BasisConvention convention;
    public final Mat w;
    public final Linalg.SVD svd;

    public PolarimetricDesign(List<OpticalState> generators, List<OpticalState> analyzers,
                              List<int[]> pairs, BasisConvention convention,
                              Mat instrumentCorrectionP, Mat instrumentCorrectionA) {
        this.generators = generators;
        this.analyzers = analyzers;
        this.pairs = pairs;
        this.convention = convention;
        double[][] rows = new double[pairs.size()][16];
        for (int r = 0; r < pairs.size(); r++) {
            int gi = pairs.get(r)[0], ai = pairs.get(r)[1];
            double[] sGen = generators.get(gi).vector(convention);
            double[] aRow = analyzers.get(ai).vector(convention);
            if (instrumentCorrectionP != null) sGen = instrumentCorrectionP.mul(sGen);
            if (instrumentCorrectionA != null) aRow = instrumentCorrectionA.mul(aRow);
            // W row = a_row (x) s_gen^T, matching column-major stacking m[4*col + row]
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[r][4 * j + i] = sGen[i] * aRow[j];
                }
            }
        }
        this.w = Mat.of(rows);
        this.svd = Linalg.svd(this.w);
    }

    public double[] singularValues() {
        return svd.s;
    }

    public int rank(double tol) {
        return svd.rank(tol);
    }

    /** True condition number when full rank; +Inf when rank deficient. */
    public double conditionNumber(double tol) {
        int rank = rank(tol);
        if (rank < 16) return Double.POSITIVE_INFINITY;
        return svd.s[0] / svd.s[15];
    }

    /** Near-singular but technically full rank: condition beyond the safe-inversion threshold. */
    public boolean nearSingular(double safeCond) {
        double c = conditionNumber(1e-12);
        return !Double.isInfinite(c) && c > safeCond;
    }
}
