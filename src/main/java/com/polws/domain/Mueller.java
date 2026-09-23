package com.polws.domain;

import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** Mueller matrix primitives and the four physical-constraint checks used in review. */
public final class Mueller {
    private Mueller() {
    }

    /** Column-major flattening: element (i,j) -> index 4*i+j. */
    public static double[] vec(RealMatrix m) {
        double[] v = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                v[4 * i + j] = m.getEntry(i, j);
            }
        }
        return v;
    }

    public static RealMatrix unvec(double[] v) {
        RealMatrix m = MatrixUtils.createRealMatrix(4, 4);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                m.setEntry(i, j, v[4 * i + j]);
            }
        }
        return m;
    }

    /** Physical-constraint residuals. Negative residual => violated. */
    public static final class Constraints {
        public final double lowerBoundResidual;
        public final double upperBoundResidual;
        public final double cloudeMinEigen;
        public final double passivityResidual;
        public final boolean feasible;

        public Constraints(double lower, double upper, double minEigen, double passivity, boolean feasible) {
            this.lowerBoundResidual = lower;
            this.upperBoundResidual = upper;
            this.cloudeMinEigen = minEigen;
            this.passivityResidual = passivity;
            this.feasible = feasible;
        }
    }

    public static Constraints check(RealMatrix m) {
        double tol = 1e-9;
        double m00 = m.getEntry(0, 0);
        double rowCol = 0;
        for (int k = 1; k < 4; k++) {
            rowCol += Math.abs(m.getEntry(0, k)) + Math.abs(m.getEntry(k, 0));
        }
        double lower = m00 - rowCol;

        double inner = 0;
        for (int i = 1; i < 4; i++) {
            for (int j = 1; j < 4; j++) {
                inner += Math.abs(m.getEntry(i, j));
            }
        }
        double upper = 2 * m00 - (rowCol + inner);

        double minEigen = minCloudeEigen(m);

        double passivity;
        if (m00 <= 0) {
            passivity = Double.NEGATIVE_INFINITY;
        } else {
            SingularValueDecomposition svd =
                    new SingularValueDecomposition(m.scalarMultiply(1.0 / m00));
            passivity = 1.0 - svd.getSingularValues()[0];
        }

        boolean feasible = lower >= -tol && upper >= -tol
                && minEigen >= -tol * Math.max(1, Math.abs(m00)) && passivity >= -tol;
        return new Constraints(lower, upper, minEigen, passivity, feasible);
    }

    // Pauli matrices as complex [re, im] 2x2 arrays; index 0 = identity.
    private static final double[][][][] PAULI = buildPauli();

    private static double[][][][] buildPauli() {
        double[][][][] p = new double[4][2][2][2];
        // identity
        p[0][0][0] = new double[]{1, 0};
        p[0][1][1] = new double[]{1, 0};
        // sigma_x
        p[1][0][1] = new double[]{1, 0};
        p[1][1][0] = new double[]{1, 0};
        // sigma_y
        p[2][0][1] = new double[]{0, -1};
        p[2][1][0] = new double[]{0, 1};
        // sigma_z
        p[3][0][0] = new double[]{1, 0};
        p[3][1][1] = new double[]{-1, 0};
        return p;
    }

    private static double[] cmul(double[] a, double[] b) {
        return new double[]{a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]};
    }

    private static double[] cconj(double[] a) {
        return new double[]{a[0], -a[1]};
    }

    /**
     * Cloude coherency matrix T, Hermitian 4x4 (entries [re, im]), built from definition
     * M = sum_ij T_ij (sigma_i (kron) sigma_j*) and Pauli-matrix orthogonality.
     */
    public static double[][][] coherency(RealMatrix m) {
        double[][] a = m.getData();
        double[][][] t = new double[4][4][2];
        for (int k = 0; k < 4; k++) {
            for (int l = 0; l < 4; l++) {
                // T_kl = (1/4) Tr(B_kl^dag M), B_kl = sigma_k (kron) sigma_l*; B_kl^dag = sigma_k (kron) sigma_l^T
                double[] acc = {0, 0};
                for (int p = 0; p < 2; p++) {
                    for (int q = 0; q < 2; q++) {
                        for (int i = 0; i < 2; i++) {
                            for (int j = 0; j < 2; j++) {
                                // (sigma_k)_{p i}
                                double[] sk = PAULI[k][p][i];
                                // (sigma_l^T)_{q j} = (sigma_l)_{j q}
                                double[] sl = PAULI[l][j][q];
                                double mval = a[2 * p + q][2 * i + j];
                                double[] prod = cmul(sk, sl);
                                acc[0] += prod[0] * mval;
                                acc[1] += prod[1] * mval;
                            }
                        }
                    }
                }
                t[k][l][0] = acc[0] / 4.0;
                t[k][l][1] = acc[1] / 4.0;
            }
        }
        return t;
    }

    /** All four Cloude eigenvalues (real parts of Hermitian spectrum), ascending not guaranteed. */
    public static double[] cloudeEigenvalues(RealMatrix m) {
        double[][][] t = coherency(m);
        RealMatrix re = MatrixUtils.createRealMatrix(4, 4);
        RealMatrix im = MatrixUtils.createRealMatrix(4, 4);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                re.setEntry(i, j, t[i][j][0]);
                im.setEntry(i, j, t[i][j][1]);
            }
        }
        RealMatrix big = MatrixUtils.createRealMatrix(8, 8);
        big.setSubMatrix(re.getData(), 0, 0);
        big.setSubMatrix(im.scalarMultiply(-1).getData(), 0, 4);
        big.setSubMatrix(im.getData(), 4, 0);
        big.setSubMatrix(re.getData(), 4, 4);
        double[] ev8 = new EigenDecomposition(big).getRealEigenvalues();
        double[] ev = new double[4];
        for (int i = 0; i < 4; i++) {
            ev[i] = (ev8[2 * i] + ev8[2 * i + 1]) / 2.0;
        }
        return ev;
    }

    public static double minCloudeEigen(RealMatrix m) {
        double[] ev = cloudeEigenvalues(m);
        double min = Double.POSITIVE_INFINITY;
        for (double v : ev) {
            min = Math.min(min, v);
        }
        return min;
    }

    public static double depolarizationIndex(RealMatrix m) {
        double sumSq = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i != 0 || j != 0) {
                    double e = m.getEntry(i, j);
                    sumSq += e * e;
                }
            }
        }
        return Math.sqrt(sumSq) / (Math.sqrt(3.0) * Math.abs(m.getEntry(0, 0)));
    }
}
