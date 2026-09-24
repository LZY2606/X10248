package polimueller.core;

import polimueller.linalg.Matrix;

/**
 * Physical realizability of a Mueller matrix via its Hermitian coherency
 * matrix H (Cloude): a physical Mueller matrix requires H positive
 * semidefinite. Non-physical candidates are FLAGGED with their violation
 * residual and kept as-is; they are never projected back to the physical
 * cone and presented as the original solution.
 */
public final class Physicality {
    private Physicality() {}

    public record Check(boolean physical, double[] coherencyEigenvalues,
                        double violationResidual, String verdict) {}

    // Pauli matrices sigma_0..sigma_3 as (re, im)
    private static final double[][][] SIG_RE = {
            {{1, 0}, {0, 1}},
            {{1, 0}, {0, -1}},
            {{0, 1}, {1, 0}},
            {{0, 0}, {0, 0}}
    };
    private static final double[][][] SIG_IM = {
            {{0, 0}, {0, 0}},
            {{0, 0}, {0, 0}},
            {{0, 0}, {0, 0}},
            {{0, -1}, {1, 0}}
    };

    public static Check check(double[][] m) {
        double[][] hRe = new double[4][4];
        double[][] hIm = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                // Pauli matrices are Hermitian, so sigma_j^dagger = sigma_j.
                double[][][] k = complexKron(SIG_RE[i], SIG_IM[i], SIG_RE[j], SIG_IM[j]);
                for (int a = 0; a < 4; a++)
                    for (int b = 0; b < 4; b++) {
                        hRe[a][b] += m[i][j] * k[0][a][b];
                        hIm[a][b] += m[i][j] * k[1][a][b];
                    }
            }
        }
        for (int a = 0; a < 4; a++)
            for (int b = 0; b < 4; b++) { hRe[a][b] /= 4.0; hIm[a][b] /= 4.0; }

        double[] eig = Matrix.hermitianEigenvalues(hRe, hIm);
        double residual = 0;
        for (double v : eig) if (v < 0) residual += -v;
        boolean physical = residual < 1e-9;
        return new Check(physical, eig, residual,
                physical ? "PHYSICAL"
                        : "NON_PHYSICAL violation_residual=" + Matrix.fmt(residual)
                        + " min_eigen=" + Matrix.fmt(eig[eig.length - 1])
                        + " (flagged, NOT projected)");
    }

    /** (aRe + i aIm) kron (bRe + i bIm), returned as {re, im} 4x4 blocks. */
    private static double[][][] complexKron(double[][] aRe, double[][] aIm,
                                            double[][] bRe, double[][] bIm) {
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int k = 0; k < 2; k++)
                    for (int l = 0; l < 2; l++) {
                        re[2 * i + k][2 * j + l] = aRe[i][j] * bRe[k][l] - aIm[i][j] * bIm[k][l];
                        im[2 * i + k][2 * j + l] = aRe[i][j] * bIm[k][l] + aIm[i][j] * bRe[k][l];
                    }
        return new double[][][]{re, im};
    }
}
