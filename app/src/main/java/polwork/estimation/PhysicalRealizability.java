package polwork.estimation;

import org.ejml.data.Complex_F64;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition;
import polwork.domain.PhysicalConstraints;

import java.util.ArrayList;
import java.util.List;

/**
 * Cloude coherency-matrix test for physical realizability.
 * With Pauli matrices sigma_0..sigma_3, any real 4x4 Mueller matrix expands
 * uniquely as M = sum_{a,b} H_ab sigma_a (x) sigma_b, so
 * H_ab = 1/4 sum_{mu,nu} (sigma_a (x) sigma_b)_{mu,nu} M_{mu,nu}.
 * M is physically realizable iff H is positive semidefinite. Violations are
 * reported; a flagged candidate is never projected into feasibility and then
 * presented as the original solution.
 */
public final class PhysicalRealizability {

    private static final double EIG_TOL = 1e-9;

    private PhysicalRealizability() {}

    private static final double[][][] SIG = {
            {{1, 0}, {0, 1}},
            {{1, 0}, {0, -1}},
            {{0, 1}, {1, 0}},
            {{0, 1}, {-1, 0}}
    };

    public static DMatrixRMaj coherency(double[][] m) {
        double[][] h = new double[4][4];
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                double v = 0;
                for (int mu = 0; mu < 4; mu++) {
                    for (int nu = 0; nu < 4; nu++) {
                        int r1 = mu / 2, c1 = nu / 2;
                        int r2 = mu % 2, c2 = nu % 2;
                        v += SIG[a][r1][c1] * SIG[b][r2][c2] * m[mu][nu];
                    }
                }
                h[a][b] = v / 4.0;
            }
        }
        return Linear.mat(h);
    }

    public static PhysicalConstraints evaluate(double[][] m) {
        List<String> violations = new ArrayList<>();

        double maxAbs = 0;
        for (double[] row : m) for (double v : row) maxAbs = Math.max(maxAbs, Math.abs(v));
        double passivityResidual = Math.max(0, maxAbs - 1.0);
        if (passivityResidual > 1e-6) {
            violations.add("passivity:max|m_ij|=" + fmt(maxAbs) + ">1");
        }

        DMatrixRMaj h = coherency(m);
        EigenDecomposition<DMatrixRMaj> eig = DecompositionFactory_DDRM.eig(4, true, false);
        eig.decompose(h);
        double minEig = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            Complex_F64 e = eig.getEigenvalue(i);
            minEig = Math.min(minEig, e.real);
        }
        if (minEig < -EIG_TOL) {
            violations.add("cloude:min_eigenvalue=" + fmt(minEig) + "<0");
        }

        double sumSq = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) sumSq += m[i][j] * m[i][j];
        double di = 0;
        if (Math.abs(m[0][0]) > 1e-12) {
            di = Math.sqrt(Math.max(0, sumSq - m[0][0] * m[0][0])) / (Math.sqrt(3) * m[0][0]);
        }

        boolean realizable = violations.isEmpty();
        return new PhysicalConstraints(passivityResidual, minEig, realizable,
                String.join(";", violations), di);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4g", v);
    }
}
