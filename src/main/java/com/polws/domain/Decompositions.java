package com.polws.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-candidate decompositions. Every decomposition is a candidate derived from a
 * specific Mueller candidate; non-physical inputs are still decomposed (guarded) and the
 * candidate keeps the violation flags instead of being silently projected.
 */
public final class Decompositions {
    private Decompositions() {
    }

    public static final class Decomp {
        public final String family;
        public final Map<String, Double> scalars = new LinkedHashMap<>();
        public final double[][] mD;
        public final double[][] mR;
        public final double[][] mDelta;
        public final String diagnostic;

        Decomp(String family, double[][] mD, double[][] mR, double[][] mDelta, String diagnostic) {
            this.family = family;
            this.mD = mD;
            this.mR = mR;
            this.mDelta = mDelta;
            this.diagnostic = diagnostic;
        }
    }

    private static double norm3(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /** Lu-Chipman polar decomposition M = M_delta M_R M_D (normalized Mueller matrix). */
    public static Decomp luChipman(RealMatrix mRaw) {
        double m00 = mRaw.getEntry(0, 0);
        if (Math.abs(m00) < 1e-15) {
            return new Decomp("LU_CHIPMAN", null, null, null, "M00_TOO_SMALL: cannot normalize");
        }
        RealMatrix m = mRaw.scalarMultiply(1.0 / m00);
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) {
            d[i] = m.getEntry(0, i + 1);
        }
        double dNorm = norm3(d);
        double[][] md = new double[4][4];
        md[0][0] = 1;
        for (int i = 1; i < 4; i++) {
            md[0][i] = d[i - 1];
            md[i][0] = d[i - 1];
        }
        double eta = Math.sqrt(Math.max(0, 1 - dNorm * dNorm));
        double[][] dd = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                dd[i][j] = eta * (i == j ? 1 : 0) + (1 - eta) * d[i] * d[j] / Math.max(dNorm * dNorm, 1e-30);
            }
        }
        for (int i = 0; i < 3; i++) {
            System.arraycopy(dd[i], 0, md[i + 1], 1, 3);
        }
        RealMatrix mD = MatrixUtils.createRealMatrix(md);

        RealMatrix mDinv;
        try {
            mDinv = new org.apache.commons.math3.linear.LUDecomposition(mD).getSolver().getInverse();
        } catch (Exception e) {
            return new Decomp("LU_CHIPMAN", md, null, null, "DIATTENUATOR_SINGULAR");
        }
        RealMatrix mp = m.multiply(mDinv); // M' = M_delta M_R

        double[][] mr = new double[4][4];
        mr[0][0] = 1;
        for (int i = 1; i < 4; i++) {
            mr[i][0] = 0;
            mr[0][i] = 0;
        }
        double[] p = new double[3];
        double[] q = new double[3];
        for (int i = 0; i < 3; i++) {
            p[i] = mp.getEntry(i + 1, 0);
            q[i] = mp.getEntry(0, i + 1);
        }
        double[][] cross = crossMatrix(p);
        double[] pCrossQ = new double[]{
                p[1] * q[2] - p[2] * q[1],
                p[2] * q[0] - p[0] * q[2],
                p[0] * q[1] - p[1] * q[0]
        };
        double[][] sub = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double v = mp.getEntry(i + 1, j + 1) - cross[i][j];
                if (dNorm > 1e-12) {
                    v -= pCrossQ[i] * d[j] / dNorm;
                }
                sub[i][j] = v;
            }
        }
        RealMatrix subM = MatrixUtils.createRealMatrix(sub);
        double diagScale = dNorm > 1e-12 ? 1.0 / eta : 1.0;
        subM = subM.scalarMultiply(diagScale);
        for (int i = 0; i < 3; i++) {
            System.arraycopy(sub[i], 0, mr[i + 1], 1, 3);
        }
        RealMatrix mR = MatrixUtils.createRealMatrix(mr);
        RealMatrix mDelta = mp.multiply(new org.apache.commons.math3.linear.LUDecomposition(mR).getSolver().getInverse());

        Decomp res = new Decomp("LU_CHIPMAN", md, mr, mDelta.getData(), null);
        res.scalars.put("diattenuation", dNorm);
        res.scalars.put("diattenuationAxisAzimuthDeg", Math.toDegrees(0.5 * Math.atan2(d[1], d[0])));
        // retardance from rotation angle of 3x3 retarder block
        double tr = subM.getTrace();
        double cosR = Math.max(-1, Math.min(1, (tr - 1) / 2.0));
        double retardance = Math.acos(cosR);
        res.scalars.put("retardanceDeg", Math.toDegrees(retardance));
        double[] axis = new double[]{
                subM.getEntry(2, 1) - subM.getEntry(1, 2),
                subM.getEntry(0, 2) - subM.getEntry(2, 0),
                subM.getEntry(1, 0) - subM.getEntry(0, 1)
        };
        double an = norm3(axis);
        if (an > 1e-12) {
            res.scalars.put("retardanceAxisPolarDeg", Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, axis[2] / an)))));
        }
        // depolarization power from mDelta eigenvalues (3x3 block)
        double dtrace = 0;
        for (int i = 0; i < 3; i++) {
            dtrace += mDelta.getEntry(i + 1, i + 1);
        }
        res.scalars.put("depolarizationPower", 1 - Math.abs(dtrace) / 3.0);
        res.scalars.put("depolarizationIndex", Mueller.depolarizationIndex(mRaw));
        return res;
    }

    /** Cloude coherency / covariance decomposition: eigenvalues and entropy. */
    public static Decomp cloude(RealMatrix mRaw) {
        double m00 = mRaw.getEntry(0, 0);
        RealMatrix m = Math.abs(m00) < 1e-15 ? mRaw : mRaw.scalarMultiply(1.0 / m00);
        double[] ev = Mueller.cloudeEigenvalues(m);
        java.util.Arrays.sort(ev);
        double h = 0;
        double sum = 0;
        for (double v : ev) {
            sum += Math.max(0, v);
        }
        for (double v : ev) {
            if (v > 1e-15) {
                double p = v / sum;
                h -= p * (Math.log(p) / Math.log(4));
            }
        }
        Decomp res = new Decomp("CLOUDE", null, null, null, ev[0] < -1e-9 ? "NON_PSD_COHERENCY" : null);
        res.scalars.put("cloudeLambdaMin", ev[0]);
        res.scalars.put("cloudeLambdaMax", ev[3]);
        res.scalars.put("cloudeEntropy", h);
        res.scalars.put("depolarizationIndex", Mueller.depolarizationIndex(mRaw));
        return res;
    }

    /** Simple row/column diattenuation and polarizance readouts, kept as a third candidate family. */
    public static Decomp basicIndices(RealMatrix mRaw) {
        Decomp res = new Decomp("BASIC_INDICES", null, null, null, null);
        double[] d = new double[3];
        double[] p = new double[3];
        double m00 = mRaw.getEntry(0, 0);
        for (int i = 0; i < 3; i++) {
            d[i] = mRaw.getEntry(0, i + 1);
            p[i] = mRaw.getEntry(i + 1, 0);
        }
        res.scalars.put("diattenuation", norm3(d) / Math.abs(m00));
        res.scalars.put("polarizance", norm3(p) / Math.abs(m00));
        res.scalars.put("depolarizationIndex", Mueller.depolarizationIndex(mRaw));
        return res;
    }

    private static double[][] crossMatrix(double[] p) {
        return new double[][]{
                {0, p[2], -p[1]},
                {-p[2], 0, p[0]},
                {p[1], -p[0], 0}
        };
    }
}
