package com.polarlab;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.List;

/** Mueller-matrix primitives: instrument matrices, Cloude coherency matrix, physical constraints. */
public final class Mueller {
    private Mueller() {}

    public static double[][] identity4() {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            m[i][i] = 1;
        }
        return m;
    }

    /**
     * Build a generic 4x4 instrument matrix (PSA or PSG). Rows are the Stokes states produced when
     * feeding basis inputs; the matrix carries the Stokes-order convention of its source data.
     */
    public static RealMatrix instrumentMatrix(double[][] rows) {
        if (rows.length != 4) {
            throw new IllegalArgumentException("instrument matrix needs exactly 4 state rows");
        }
        for (double[] r : rows) {
            if (r.length != 4) {
                throw new IllegalArgumentException("instrument state rows must be length 4");
            }
        }
        return new Array2DRowRealMatrix(rows, true);
    }

    /**
     * One row of the measurement design matrix: analyzerStokes (x) generatorStokes,
     * in canonical S0123 ordering, matched to row-major vec(M) (index 4*i+j == M_ij).
     */
    public static double[] designRow(double[] analyzerCanon, double[] generatorCanon) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                row[4 * i + j] = analyzerCanon[i] * generatorCanon[j];
            }
        }
        return row;
    }

    public static double predict(double[] analyzerCanon, double[] generatorCanon, double[] mVec) {
        double[] row = designRow(analyzerCanon, generatorCanon);
        double v = 0;
        for (int k = 0; k < 16; k++) {
            v += row[k] * mVec[k];
        }
        return v;
    }

    // ----- Cloude coherency matrix via the Jones-superoperator construction -----
    // Stokes basis: P0=I, P1=sigma_z (0/90), P2=sigma_x (+/-45), P3=sigma_y (circular).

    private static final Complex I = Complex.ONE;
    private static final Complex Z = Complex.ZERO;

    static Complex[][] pauli(int k) {
        switch (k) {
            case 0: return new Complex[][] {{I, Z}, {Z, I}};
            case 1: return new Complex[][] {{I, Z}, {Z, I.negate()}};
            case 2: return new Complex[][] {{Z, I}, {I, Z}};
            case 3: return new Complex[][] {{Z, Complex.I.negate()}, {Complex.I, Z}};
            default: throw new IllegalArgumentException();
        }
    }

    private static Complex tr(Complex[][] a) {
        return a[0][0].add(a[1][1]);
    }

    private static Complex[][] mul(Complex[][] a, Complex[][] b) {
        Complex[][] c = new Complex[2][2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                c[i][j] = a[i][0].multiply(b[0][j]).add(a[i][1].multiply(b[1][j]));
            }
        }
        return c;
    }

    private static Complex[][] dagger(Complex[][] a) {
        return new Complex[][] {{a[0][0].conjugate(), a[1][0].conjugate()},
                                {a[0][1].conjugate(), a[1][1].conjugate()}};
    }

    private static Complex[][] pauli() { return new Complex[2][2]; }

    /**
     * Mueller matrix from a Hermitian coherency matrix T:
     * M_ij = 1/2 tr(P_i A(P_j)), A(X) = sum_pq T_pq beta_p X beta_q^dagger.
     */
    public static double[][] muellerFromCoherency(Complex[][] t) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Complex[][] acc = new Complex[][] {{Z, Z}, {Z, Z}};
                for (int p = 0; p < 4; p++) {
                    for (int q = 0; q < 4; q++) {
                        Complex[][] bp = pauli(p);
                        Complex[][] bq = pauli(q);
                        // beta_p P_j beta_q^dagger
                        Complex[][] x = mul(mul(bp, pauli(j)), dagger(bq));
                        Complex tpq = t[p][q];
                        for (int r = 0; r < 2; r++) {
                            for (int c = 0; c < 2; c++) {
                                acc[r][c] = acc[r][c].add(tpq.multiply(x[r][c]));
                            }
                        }
                    }
                }
                m[i][j] = 0.5 * tr(mul(pauli(i), acc)).getReal();
            }
        }
        return m;
    }

    /**
     * Coherency matrix T from a Mueller matrix: invert the exact 16-real linear map
     * between the Hermitian 4x4 T and the 16 Mueller entries.
     */
    public static Complex[][] coherencyFromMueller(double[][] m) {
        // Hermitian parameters: [T00..T33, ReT01,ImT01,ReT02,ImT02,ReT03,ImT03,
        //                         ReT12,ImT12,ReT13,ImT13,ReT23,ImT23]
        double[][] l = new double[16][16];
        // diagonal basis
        for (int p = 0; p < 4; p++) {
            Complex[][] t = new Complex[4][4];
            initHermitian(t);
            t[p][p] = I;
            double[][] mm = muellerFromCoherency(t);
            for (int e = 0; e < 16; e++) {
                l[e][p] = mm[e / 4][e % 4];
            }
        }
        int[][] pairs = {{0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}};
        for (int z = 0; z < pairs.length; z++) {
            int a = pairs[z][0];
            int b = pairs[z][1];
            for (int phase = 0; phase < 2; phase++) {
                Complex v = phase == 0 ? I : Complex.I;
                Complex[][] t = new Complex[4][4];
                initHermitian(t);
                t[a][b] = v;
                t[b][a] = v.conjugate();
                double[][] mm = muellerFromCoherency(t);
                int col = 4 + 2 * z + phase;
                for (int e = 0; e < 16; e++) {
                    l[e][col] = mm[e / 4][e % 4];
                }
            }
        }
        RealMatrix lm = new Array2DRowRealMatrix(l, false);
        RealMatrix inv = Mat.inverseFullRank(lm);
        double[] mv = new double[16];
        for (int e = 0; e < 16; e++) {
            mv[e] = m[e / 4][e % 4];
        }
        double[] pv = inv.operate(mv);
        Complex[][] t = new Complex[4][4];
        initHermitian(t);
        for (int p = 0; p < 4; p++) {
            t[p][p] = new Complex(pv[p], 0);
        }
        for (int z = 0; z < pairs.length; z++) {
            int a = pairs[z][0];
            int b = pairs[z][1];
            double re = pv[4 + 2 * z];
            double im = pv[4 + 2 * z + 1];
            t[a][b] = new Complex(re, im);
            t[b][a] = new Complex(re, -im);
        }
        return t;
    }

    private static void initHermitian(Complex[][] t) {
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                t[i][j] = Z;
            }
        }
    }

    public static double[] coherencyEigenvalues(double[][] m) {
        return Mat.eigHermitian(coherencyFromMueller(m));
    }

    /** One checked physical-realizability condition with its observed residual. */
    public record Violation(String constraint, double observed, double bound, boolean ok) {}

    /** Evaluate physical constraints. Violations are FLAGGED ONLY; the candidate is never projected. */
    public static List<Violation> physicalViolations(double[][] m, double tol) {
        List<Violation> out = new ArrayList<>();
        double m00 = m[0][0];
        out.add(new Violation("M00 > 0", -m00, tol, m00 > -tol));
        for (int j = 1; j < 4; j++) {
            out.add(new Violation("|M0" + j + "| <= M00", Math.abs(m[0][j]) - m00, tol, Math.abs(m[0][j]) <= m00 + tol));
        }
        for (int i = 1; i < 4; i++) {
            out.add(new Violation("|M" + i + "0| <= M00", Math.abs(m[i][0]) - m00, tol, Math.abs(m[i][0]) <= m00 + tol));
        }
        double[] eig = coherencyEigenvalues(m);
        for (int k = 0; k < 4; k++) {
            out.add(new Violation("lambda_" + k + "(T) >= 0", -eig[k], tol, eig[k] >= -tol));
        }
        double sumSq = 0;
        for (int i = 1; i < 4; i++) {
            for (int j = 1; j < 4; j++) {
                sumSq += m[i][j] * m[i][j];
            }
        }
        double depol = Math.sqrt(sumSq / 3.0) / m00;
        out.add(new Violation("depolarization index <= 1", depol - 1.0, tol, depol <= 1.0 + tol));
        return out;
    }

    public static boolean isPhysical(double[][] m, double tol) {
        return physicalViolations(m, tol).stream().allMatch(Violation::ok);
    }
}
