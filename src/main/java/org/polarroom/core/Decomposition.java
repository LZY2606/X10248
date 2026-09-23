package org.polarroom.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mueller matrix decomposition candidates.
 *
 * <p>Two polar-decomposition families are offered as separate, equally
 * retrievable candidates: the forward Lu-Chipman decomposition
 * M = M_Delta M_R M_D and the backward (reverse) form M' = M_D M_R M_Delta,
 * obtained on the transpose via the Ossikovski relation. A third candidate is
 * the Cloude coherent sum; it is diagnostic and never overwrites the raw fit.
 */
public final class Decomposition {

    private Decomposition() {
    }

    public static final class Factor {
        public double[][] matrix;
        public Map<String, Double> scalars = new LinkedHashMap<>();
    }

    public static final class Result {
        public final Map<String, Factor> factors = new LinkedHashMap<>();
        public double roundTripResidual;
        public boolean valid;
        public String diagnostic;
    }

    /** Forward (standard) Lu-Chipman decomposition. */
    public static Result forwardLuChipman(double[][] mIn) {
        double[][] m = copy(mIn);
        Result res = new Result();
        try {
            double m00 = m[0][0];
            double[] d = {m[0][1], m[0][2], m[0][3]};
            double dNorm = norm(d) / m00;

            Factor diat = new Factor();
            diat.matrix = diattenuator(m00, d);
            diat.scalars.put("diattenuation", dNorm);
            res.factors.put("M_D", diat);

            double[][] m1 = LinAlg.multiply(m, LinAlg.inverse(diat.matrix));
            double[][] m3 = new double[3][3];
            for (int i = 0; i < 3; i++) {
                System.arraycopy(m1[i + 1], 1, m3[i], 0, 3);
            }
            double[][] o = new double[3][3];
            double[][] p = new double[3][3];
            LinAlg.polarDecomposition3(m3, o, p);

            Factor ret = new Factor();
            ret.matrix = embed(o);
            ret.scalars.put("retardance_rad", retardance(o));
            res.factors.put("M_R", ret);

            double[] mRow0 = {m1[1][0], m1[2][0], m1[3][0]};
            double[][] pInv = invert3(p);
            double[] pv = LinAlg.multiply(pInv, mRow0);

            Factor depol = new Factor();
            depol.matrix = new double[][]{
                    {1.0, 0, 0, 0},
                    {pv[0], p[0][0], p[0][1], p[0][2]},
                    {pv[1], p[1][0], p[1][1], p[1][2]},
                    {pv[2], p[2][0], p[2][1], p[2][2]}
            };
            double depolPower = depolarizationPower(depol.matrix);
            depol.scalars.put("depolarization_power", depolPower);
            depol.scalars.put("polarizance", norm(pv));
            res.factors.put("M_Delta", depol);

            double[][] rec = LinAlg.multiply(LinAlg.multiply(depol.matrix, ret.matrix), diat.matrix);
            res.roundTripResidual = maxDiff(rec, mIn);
            res.valid = res.roundTripResidual < 1e-6;
            res.diagnostic = res.valid ? "ok" : "roundtrip_residual_large";
        } catch (RuntimeException e) {
            res.valid = false;
            res.diagnostic = "decomposition_failed: " + e.getMessage();
        }
        return res;
    }

    /**
     * Backward (reverse) Lu-Chipman: decompose M^T forward, then transpose the
     * factors in reverse order (Ossikovski 2008).
     */
    public static Result backwardLuChipman(double[][] m) {
        Result fwd = forwardLuChipman(LinAlg.transpose(m));
        Result res = new Result();
        res.valid = fwd.valid;
        res.diagnostic = fwd.diagnostic;
        res.roundTripResidual = fwd.roundTripResidual;
        Factor bD = fwd.factors.get("M_Delta");
        Factor bR = fwd.factors.get("M_R");
        Factor bDiat = fwd.factors.get("M_D");
        putTransposed(res, "M_D", bDiat);
        putTransposed(res, "M_R", bR);
        putTransposed(res, "M_Delta", bD);
        return res;
    }

    private static void putTransposed(Result res, String name, Factor src) {
        Factor f = new Factor();
        f.matrix = LinAlg.transpose(src.matrix);
        f.scalars.putAll(src.scalars);
        res.factors.put(name, f);
    }

    /**
     * Cloude target decomposition of H: keeps the four rank-1 components with
     * their weights. This is the diagnostic candidate used to expose a
     * non-physical (negative-eigenvalue) result rather than project it away.
     */
    public static Result cloudeTargets(double[][] m) {
        Result res = new Result();
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        Mueller.coherency(m, re, im);
        // Jacobi-style diagonalisation of the Hermitian H via the 8x8 embedding:
        // recover eigenvalues/eigenvectors with Apache commons directly.
        org.apache.commons.math3.linear.RealMatrix big =
                org.apache.commons.math3.linear.MatrixUtils.createRealMatrix(8, 8);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                big.setEntry(i, j, re[i][j]);
                big.setEntry(4 + i, 4 + j, re[i][j]);
                big.setEntry(i, 4 + j, -im[i][j]);
                big.setEntry(4 + i, j, im[i][j]);
            }
        }
        org.apache.commons.math3.linear.EigenDecomposition eig =
                new org.apache.commons.math3.linear.EigenDecomposition(big);
        double[] ev = eig.getRealEigenvalues();
        for (int k = 0; k < 4; k++) {
            double lambda = ev[2 * k + 1];
            double[][] hr = new double[4][4];
            double[][] hi = new double[4][4];
            double[] vr = new double[4];
            double[] vi = new double[4];
            for (int i = 0; i < 4; i++) {
                vr[i] = eig.getEigenvector(2 * k + 1).getEntry(i);
                vi[i] = eig.getEigenvector(2 * k + 1).getEntry(4 + i);
            }
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    hr[i][j] = vr[i] * vr[j] + vi[i] * vi[j];
                    hi[i][j] = vi[i] * vr[j] - vr[i] * vi[j];
                }
            }
            Factor f = new Factor();
            f.matrix = Mueller.fromCoherency(hr, hi);
            f.scalars.put("coherency_weight", lambda);
            res.factors.put("T" + k, f);
        }
        double[][] rec = new double[4][4];
        for (Factor f : res.factors.values()) {
            double w = f.scalars.get("coherency_weight");
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rec[i][j] += w * f.matrix[i][j];
                }
            }
        }
        res.roundTripResidual = maxDiff(rec, m);
        res.valid = res.roundTripResidual < 1e-6;
        res.diagnostic = "cloude_targets";
        return res;
    }

    private static double[][] diattenuator(double m00, double[] dVec) {
        double d = norm(dVec);
        double[][] md = new double[4][4];
        md[0][0] = m00;
        for (int k = 0; k < 3; k++) {
            md[0][k + 1] = dVec[k];
            md[k + 1][0] = dVec[k];
        }
        double factor = m00 * (1.0 - Math.sqrt(Math.max(0.0, 1.0 - d * d))) / Math.max(d * d, 1e-30);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                md[i + 1][j + 1] = m00 * Math.sqrt(Math.max(0.0, 1.0 - d * d)) * (i == j ? 1.0 : 0.0)
                        + factor * dVec[i] * dVec[j];
            }
        }
        return md;
    }

    private static double[][] embed(double[][] o3) {
        double[][] r = new double[4][4];
        r[0][0] = 1.0;
        for (int i = 0; i < 3; i++) {
            System.arraycopy(o3[i], 0, r[i + 1], 1, 3);
        }
        return r;
    }

    private static double retardance(double[][] o) {
        double tr = o[0][0] + o[1][1] + o[2][2];
        double cos = Math.max(-1.0, Math.min(1.0, (tr - 1.0) / 2.0));
        return Math.acos(cos);
    }

    private static double depolarizationPower(double[][] md) {
        double a = (md[1][1] + md[2][2] + md[3][3]) / (3.0 * md[0][0]);
        double b = Math.sqrt(md[1][1] * md[1][1] + md[2][2] * md[2][2] + md[3][3] * md[3][3])
                / (Math.sqrt(3.0) * md[0][0]);
        return 1.0 - a;
    }

    private static double norm(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x * x;
        }
        return Math.sqrt(s);
    }

    private static double[][] invert3(double[][] m) {
        double[][] big = new double[4][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                big[i][j] = m[i][j];
            }
        }
        big[3][3] = 1.0;
        double[][] inv = LinAlg.inverse(big);
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(inv[i], 0, r[i], 0, 3);
        }
        return r;
    }

    private static double maxDiff(double[][] a, double[][] b) {
        double d = 0.0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                d = Math.max(d, Math.abs(a[i][j] - b[i][j]));
            }
        }
        return d;
    }

    private static double[][] copy(double[][] m) {
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i].clone();
        }
        return c;
    }
}
