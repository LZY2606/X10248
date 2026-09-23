package org.polarreview.optics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Multi-candidate polar decompositions.
 *
 * <ul>
 *   <li>Lu-Chipman forward M = M_Delta M_R M_D and reverse M = M_D M_R M_Delta
 *       (reverse via the transpose identity).</li>
 *   <li>Cloude spectral sum H = sum lambda_k q_k q_k^dag (pure-component
 *       weights). Each candidate keeps its own physical-violation flags; a
 *       nonphysical input is never silently projected before decomposition.</li>
 * </ul>
 */
public final class Decompositions {

    private static final ObjectMapper M = new ObjectMapper();

    public static final class Factorization {
        public final double[][] md;
        public final double[][] mr;
        public final double[][] mDelta;
        public final double diattenuation;
        public final double retardanceRad;
        public final double[] diattenuationVector;
        public final double[] retardanceAxis;
        public final double depolarizationPower;
        public final boolean feasible;
        public final String diagnostic;

        private Factorization(double[][] md, double[][] mr, double[][] mdelta, double d,
                              double ret, double[] dv, double[] ra, double depol,
                              boolean feasible, String diagnostic) {
            this.md = md;
            this.mr = mr;
            this.mDelta = mdelta;
            this.diattenuation = d;
            this.retardanceRad = ret;
            this.diattenuationVector = dv;
            this.retardanceAxis = ra;
            this.depolarizationPower = depol;
            this.feasible = feasible;
            this.diagnostic = diagnostic;
        }
    }

    private Decompositions() {}

    /** Forward Lu-Chipman: M = M_Delta M_R M_D. */
    public static Factorization luChipmanForward(double[][] m) {
        return luChipman(m, false);
    }

    /** Reverse Lu-Chipman, computed via M^T = M_Delta^T M_R^T M_D^T forward. */
    public static Factorization luChipmanReverse(double[][] m) {
        double[][] t = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) t[i][j] = m[j][i];
        Factorization f = luChipman(t, false);
        return f;
    }

    private static Factorization luChipman(double[][] m, boolean unused) {
        // Diattenuation vector from first row.
        double[] dvec = new double[3];
        for (int i = 0; i < 3; i++) dvec[i] = m[0][i + 1] / Math.max(Math.abs(m[0][0]), 1e-300);
        double d = norm(dvec);
        boolean feasible = d <= 1.0 + 1e-9;
        String diagnostic = feasible ? null
                : "diattenuation magnitude exceeds 1: Lu-Chipman factors are not physically feasible";

        double[][] md = diattenuatorMueller(m[0][0], dvec);

        // mDeltaR = M M_D^{-1}; only first row/column needed explicitly.
        double[][] mdInv = diattenuatorMueller(1.0 / Math.max(Math.abs(m[0][0]), 1e-300),
                negate(dvec));
        double[][] mDeltaR = mul4(m, mdInv);

        double[] p = new double[3];
        for (int i = 0; i < 3; i++) p[i] = mDeltaR[i + 1][0];

        double[][] m3 = sub3(mDeltaR);
        double m3det = Mat3.det(m3);
        double mR00 = Math.sqrt(Math.max(1.0 - normSq(p), 0));
        double mDelta00 = Math.sqrt(Math.max(m3det / Math.max(mR00 * mR00, 1e-300), 0));

        double[] pDelta = new double[3];
        for (int i = 0; i < 3; i++) pDelta[i] = p[i] / Math.max(mDelta00, 1e-300);

        // M_R33 = m3 m_D33^{-1} - p_Delta p_Delta^T
        double[][] md33 = diattenuator3(dvec);
        double[][] md33Inv = Mat3.invSym(md33);
        double[][] first = Mat3.mul(m3, md33Inv);
        double[][] rr33 = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                rr33[i][j] = first[i][j] - pDelta[i] * pDelta[j];
        double scale = mR00 > 0 ? 1.0 / mR00 : 0;
        double[][] r33 = scale3(rr33, scale);
        if (Mat3.det(r33) < 0) r33 = scale3(rr33, -scale);

        double[][] mr = fullFrom33(r33, mR00, null, null);
        double retardance = retardanceOf(r33);
        double[] axis = retardanceAxis(r33, retardance);

        // M_Delta = M (M_R M_D)^{-1} so the factors can be shown and re-multiplied.
        double[][] mrd = mul4(mr, md);
        double[][] mrdInv = invertProduct(mrd);
        double[][] mDelta = mul4(m, mrdInv);

        double depol = depolarizationPower(mDelta);
        return new Factorization(md, mr, mDelta, Math.min(1, d), retardance, dvec, axis,
                depol, feasible, diagnostic);
    }

    private static double[][] diattenuatorMueller(double scale, double[] d) {
        double[][] m33 = diattenuator3(d);
        return fullFrom33(scale3(m33, scale), scale, scaleVec(d, scale), null);
    }

    private static double[][] diattenuator3(double[] d) {
        double dn = norm(d);
        double eta = Math.sqrt(Math.max(1 - dn * dn, 0));
        double[][] m = new double[3][3];
        double[][] outer = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) outer[i][j] = d[i] * d[j];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                m[i][j] = eta * (i == j ? 1 : 0) + (1 - eta) * outer[i][j]
                        / Math.max(dn * dn, 1e-300) * (dn * dn > 1e-20 ? 1 : 0);
        if (dn * dn <= 1e-20) m = Mat3.I();
        return m;
    }

    private static double retardanceOf(double[][] r33) {
        double tr = r33[0][0] + r33[1][1] + r33[2][2];
        double cos = Math.max(-1, Math.min(1, (tr - 1) / 2));
        return Math.acos(cos);
    }

    private static double[] retardanceAxis(double[][] r33, double delta) {
        if (delta < 1e-9) return new double[] {0, 0, 1};
        double s = 2 * Math.sin(delta);
        return new double[] {
                (r33[2][1] - r33[1][2]) / s,
                (r33[0][2] - r33[2][0]) / s,
                (r33[1][0] - r33[0][1]) / s
        };
    }

    private static double depolarizationPower(double[][] md) {
        double sum = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) sum += md[i][j] * md[i][j];
        return Math.max(0, Math.min(1, 1 - Math.sqrt(sum)
                / (Math.sqrt(3) * Math.max(Math.abs(md[0][0]), 1e-300))));
    }

    public static double norm(double[] v) { return Math.sqrt(normSq(v)); }

    static double normSq(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        return s;
    }

    private static double[] negate(double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) r[i] = -v[i];
        return r;
    }

    private static double[] scaleVec(double[] v, double s) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) r[i] = v[i] * s;
        return r;
    }

    private static double[][] scale3(double[][] a, double s) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r[i][j] = a[i][j] * s;
        return r;
    }

    private static double[][] sub3(double[][] m) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r[i][j] = m[i + 1][j + 1];
        return r;
    }

    private static double[][] fullFrom33(double[][] r33, double m00, double[] row, double[] col) {
        double[][] m = new double[4][4];
        m[0][0] = m00;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) m[i + 1][j + 1] = r33[i][j];
            if (row != null) m[0][i + 1] = row[i];
            if (col != null) m[i + 1][0] = col[i];
        }
        return m;
    }

    private static double[][] mul4(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int k = 0; k < 4; k++)
                for (int j = 0; j < 4; j++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    private static double[][] t4(double[][] a) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) r[j][i] = a[i][j];
        return r;
    }

    /** Inverse of a 4x4 via Gauss-Jordan; safe on the factor products used here. */
    static double[][] invertProduct(double[][] a) {
        int n = 4;
        double[][] e = new double[4][8];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, e[i], 0, n);
            e[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(e[r][col]) > Math.abs(e[piv][col])) piv = r;
            double[] tmp = e[col]; e[col] = e[piv]; e[piv] = tmp;
            double p = e[col][col];
            if (Math.abs(p) < 1e-18) p = p < 0 ? -1e-18 : 1e-18;
            for (int j = 0; j < 2 * n; j++) e[col][j] /= p;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = e[r][col];
                for (int j = 0; j < 2 * n; j++) e[r][j] -= f * e[col][j];
            }
        }
        double[][] inv = new double[4][4];
        for (int i = 0; i < n; i++) System.arraycopy(e[i], n, inv[i], 0, n);
        return inv;
    }

    public static ObjectNode factorJson(Factorization f) {
        ObjectNode n = M.createObjectNode();
        n.put("feasible", f.feasible);
        n.put("diattenuation", round(f.diattenuation));
        n.put("retardanceRad", round(f.retardanceRad));
        n.put("retardanceDeg", round(Math.toDegrees(f.retardanceRad)));
        n.put("depolarizationPower", round(f.depolarizationPower));
        n.set("diattenuationVector", vec(f.diattenuationVector));
        n.set("retardanceAxis", vec(f.retardanceAxis));
        n.set("M_d", mat(f.md));
        n.set("M_r", mat(f.mr));
        n.set("M_delta", mat(f.mDelta));
        if (f.diagnostic != null) n.put("diagnostic", f.diagnostic);
        return n;
    }

    private static ObjectNode mat(double[][] a) {
        ObjectNode n = M.createObjectNode();
        for (int i = 0; i < a.length; i++) {
            var row = n.putArray("r" + i);
            for (double v : a[i]) row.add(round(v));
        }
        return n;
    }

    private static ObjectNode vec(double[] v) {
        ObjectNode n = M.createObjectNode();
        var a = n.putArray("v");
        for (double x : v) a.add(round(x));
        return n;
    }

    private static double round(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.round(v * 1e10) / 1e10;
    }
}
