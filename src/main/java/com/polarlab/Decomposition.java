package com.polarlab;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decomposition candidates for a reviewed Mueller matrix.
 * Every candidate keeps feasibility flags; non-physical input is not projected away.
 */
public final class Decomposition {
    private Decomposition() {}

    public record Candidate(String kind, Map<String, double[]> params, boolean feasible, String note) {}

    // ----- Cloude sum decomposition M = sum_k lambda_k M_Jk -----
    public static Candidate cloude(double[][] m) {
        Complex[][] t = Mueller.coherencyFromMueller(m);
        // numerical Hermitian form
        double[] eig = Mat.eigHermitian(t);
        // derive eigen-pure Mueller matrices via projectors
        List<double[]> lambdas = new ArrayList<>();
        List<double[][]> pureMs = new ArrayList<>();
        // Real eigendecomposition on doubled matrix recovers vectors; recompute with 8x8
        int n = 4;
        RealMatrix big = Mat.zeros(8, 8);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Complex z = t[i][j];
                big.setEntry(i, j, z.getReal());
                big.setEntry(n + i, n + j, z.getReal());
                big.setEntry(i, n + j, -z.getImaginary());
                big.setEntry(n + i, j, z.getImaginary());
            }
        }
        EigenDecomposition ed = new EigenDecomposition(big);
        boolean feasible = true;
        Map<String, double[]> params = new LinkedHashMap<>();
        for (int k = 3; k >= 0; k--) { // descending eigenvalues
            double lambda = ed.getRealEigenvalue(k) / 1.0; // doubled form shares eigenvalues
            if (lambda < -1e-9) {
                feasible = false;
            }
            double[] re = new double[4];
            double[] im = new double[4];
            for (int i = 0; i < 4; i++) {
                re[i] = ed.getEigenvector(k).getEntry(i);
                im[i] = ed.getEigenvector(k).getEntry(n + i);
            }
            Complex[][] proj = new Complex[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    Complex vi = new Complex(re[i], im[i]);
                    Complex vj = new Complex(re[j], -im[j]);
                    proj[i][j] = vi.multiply(vj);
                }
            }
            double[][] mk = Mueller.muellerFromCoherency(proj);
            double norm = mk[0][0];
            if (Math.abs(norm) > 1e-14) {
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        mk[i][j] /= norm;
                    }
                }
            }
            lambdas.add(new double[] {Math.max(0, lambda), lambda}); // reported and raw
            pureMs.add(mk);
        }
        params.put("eigenvalues", new double[] {lambdas.get(0)[1], lambdas.get(1)[1],
                lambdas.get(2)[1], lambdas.get(3)[1]});
        // entropy
        double tr = 0;
        for (double[] l : lambdas) {
            tr += Math.max(0, l[0]);
        }
        double entropy = 0;
        for (double[] l : lambdas) {
            double p = tr > 0 ? Math.max(0, l[0]) / tr : 0;
            if (p > 0) {
                entropy -= p * Math.log(p) / Math.log(4);
            }
        }
        params.put("entropy", new double[] {entropy});
        params.put("mPure0", flatten(pureMs.get(0)));
        params.put("mPure1", flatten(pureMs.get(1)));
        params.put("mPure2", flatten(pureMs.get(2)));
        params.put("mPure3", flatten(pureMs.get(3)));
        return new Candidate("cloude-sum", params, feasible,
                feasible ? "M = sum lambda_k M_Jk with four pure components"
                         : "negative coherency eigenvalue: decomposition retained with feasibility flag");
    }

    // ----- Lu-Chipman polar decomposition M = M_Delta M_R M_D -----
    public static Candidate luChipman(double[][] m) {
        double m00 = m[0][0];
        double[] d = new double[] {m[1][0], m[2][0], m[3][0]};
        double dNorm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) / m00;
        boolean feasible = dNorm <= 1 + 1e-9 && m00 > 0;
        double[][] md = diattenuator(d, m00);
        // M1 block: solve M_D^{-1} on first column to get polarizance
        double[] p = new double[] {m[0][1], m[0][2], m[0][3]};
        double pNorm = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) / m00;
        // block 3x3
        double[][] m1 = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m1[i][j] = m[i + 1][j + 1];
            }
        }
        RealMatrix m1m = new Array2DRowRealMatrix(m1);
        RealMatrix dr = new Array2DRowRealMatrix(md).getSubMatrix(1, 3, 1, 3);
        RealMatrix mrest = m1m.multiply(Mat.inverseFullRank(dr));
        // polar decomposition of mrest into orthogonal MR block * symmetric MDelta block
        RealMatrix s2 = mrest.transpose().multiply(mrest);
        double[][] ret = retarderFromRotation(polarRotation(mrest));
        double retardance = Math.acos(Math.max(-1, Math.min(1,
                (mrest.getTrace() - 1) / 2.0)));
        double diattenuation = Math.min(1, dNorm);
        double polarizance = Math.min(1, pNorm);
        Map<String, double[]> params = new LinkedHashMap<>();
        params.put("diattenuationVector", new double[] {d[0] / m00, d[1] / m00, d[2] / m00});
        params.put("diattenuation", new double[] {diattenuation});
        params.put("polarizance", new double[] {polarizance});
        params.put("retardanceRad", new double[] {retardance});
        params.put("retarderMueller", flatten(ret));
        params.put("diattenuatorMueller", flatten(md));
        params.put("depolarizerBlock", flatten(s2.getData()));
        return new Candidate("lu-chipman", params, feasible,
                feasible ? "M = M_Delta M_R M_D polar decomposition"
                         : "diattenuation magnitude exceeds 1: candidate flagged, no projection applied");
    }

    public static List<Candidate> all(double[][] m) {
        List<Candidate> out = new ArrayList<>();
        out.add(cloude(m));
        out.add(luChipman(m));
        return out;
    }

    private static double[][] diattenuator(double[] d, double m00) {
        double dn = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double[][] md = new double[4][4];
        md[0][0] = m00;
        for (int i = 0; i < 3; i++) {
            md[i + 1][0] = d[i];
            md[0][i + 1] = d[i];
        }
        double eta = dn > 1e-12 ? (Math.sqrt(1 - dn * dn) - 1) / (dn * dn) : -0.5;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                md[i + 1][j + 1] = (i == j ? m00 * Math.sqrt(1 - dn * dn) : 0)
                        + m00 * eta * d[i] * d[j];
            }
        }
        return md;
    }

    /** Recover the rotation matrix from the orthogonal factor R = U V^T via SVD. */
    private static RealMatrix polarRotation(RealMatrix a) {
        var svd = new org.apache.commons.math3.linear.SingularValueDecomposition(a);
        RealMatrix u = svd.getU();
        RealMatrix v = svd.getV();
        RealMatrix r = u.multiply(v.transpose());
        if (new org.apache.commons.math3.linear.LUDecomposition(r).getDeterminant() < 0) { // guarantee SO(3)
            RealMatrix u2 = u.copy();
            for (int i = 0; i < 3; i++) {
                u2.setEntry(i, 2, -u2.getEntry(i, 2));
            }
            r = u2.multiply(v.transpose());
        }
        return r;
    }

    private static double[][] retarderFromRotation(RealMatrix r) {
        double[][] mr = new double[4][4];
        mr[0][0] = 1;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mr[i + 1][j + 1] = r.getEntry(i, j);
            }
        }
        return mr;
    }

    private static double[] flatten(double[][] a) {
        double[] v = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                v[4 * i + j] = a.length == 4 && a[0].length == 4 ? a[i][j] : (i < a.length && j < a[0].length ? a[i][j] : 0);
            }
        }
        return v;
    }
}
