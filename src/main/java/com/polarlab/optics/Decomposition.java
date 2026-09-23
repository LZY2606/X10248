package com.polarlab.optics;

import com.polarlab.math.CMat;
import com.polarlab.math.Cmplx;
import com.polarlab.math.Linalg;
import com.polarlab.math.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi-candidate Mueller decompositions.
 *
 * - Cloude spectral: M = sum_k lambda_k M_k on the coherency eigen-components (always defined).
 * - Lu-Chipman forward: M = M_delta M_R M_D (defined when intermediate determinants are usable).
 * - Lu-Chipman reverse: M = M_D M_R M_delta (defined when the transposed forward split works).
 *
 * Each candidate is labeled with the convention under which it was produced; failing
 * preconditions produce a diagnostic candidate instead of a thrown-away result.
 */
public final class Decomposition {

    public static final class SpectralComponent {
        public final double eigenvalue;
        public final Mat mueller;
        public final double weight; // normalized eigenvalue share

        SpectralComponent(double eigenvalue, Mat mueller, double weight) {
            this.eigenvalue = eigenvalue;
            this.mueller = mueller;
            this.weight = weight;
        }
    }

    public static final class CloudeResult {
        public final List<SpectralComponent> components;
        public final double entropy;
        public final double depolarizationIndex;

        CloudeResult(List<SpectralComponent> components, double entropy, double p) {
            this.components = components;
            this.entropy = entropy;
            this.depolarizationIndex = p;
        }
    }

    public static CloudeResult cloude(Mat m) {
        CMat h = PhysicalRealizability.coherency(m);
        CMat.Eigh e = h.eigh();
        double sum = 0;
        for (double v : e.eig) sum += Math.max(0, v);
        if (sum == 0) sum = 1;
        double entropy = 0;
        for (double v : e.eig) {
            double pk = Math.max(0, v) / sum;
            if (pk > 0) entropy -= pk * Math.log(pk) / Math.log(4);
        }
        double pNum = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) pNum += m.a[i][j] * m.a[i][j];
        double pIndex = m.a[0][0] != 0 ? Math.sqrt(pNum) / Math.abs(m.a[0][0]) : Double.NaN;

        List<SpectralComponent> comps = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            CMat hk = new CMat(4);
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    hk.set(r, c, e.vec[r][k].mul(e.vec[c][k].conj()).mul(e.eig[k]));
            Mat mk = PhysicalRealizability.fromCoherency(hk);
            // normalize pure component M_k so its m00 = 1 when nonzero
            double m00 = mk.a[0][0];
            if (Math.abs(m00) > 1e-12) mk = mk.mul(1.0 / m00);
            comps.add(new SpectralComponent(e.eig[k], mk, Math.max(0, e.eig[k]) / sum));
        }
        return new CloudeResult(comps, entropy, pIndex);
    }

    public static final class LCResult {
        public final String order;
        public final Mat diattenuator;
        public final Mat retarder;
        public final Mat depolarizer;
        public final double diattenuation;
        public final double retardanceRad;
        public final String diagnostic;

        LCResult(String order, Mat d, Mat r, Mat delta, double diat, double ret, String diag) {
            this.order = order;
            this.diattenuator = d;
            this.retarder = r;
            this.depolarizer = delta;
            this.diattenuation = diat;
            this.retardanceRad = ret;
            this.diagnostic = diag;
        }
    }

    /** Forward Lu-Chipman: M = M_delta M_R M_D. */
    public static LCResult luChipmanForward(Mat m) {
        // Diattenuation vector from first row
        double m00 = m.a[0][0];
        double d1 = m.a[0][1] / m00, d2 = m.a[0][2] / m00, d3 = m.a[0][3] / m00;
        double d = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
        if (d >= 1 - 1e-9) {
            return new LCResult("M_delta*M_R*M_D", null, null, null, d, Double.NaN,
                    "diattenuation magnitude " + fmt(d) + " >= 1; Lu-Chipman denominator singular");
        }
        Mat md = diattenuator(d1, d2, d3, d);
        Mat mdInv = invert4(md);
        Mat m1 = m.mul(mdInv); // M1 = M_delta M_R
        // polar decomposition of submatrix of M1 rows/cols 1..3
        Mat mR = retarderFromM1(m1);
        if (mR == null) {
            return new LCResult("M_delta*M_R*M_D", md, null, null, d, Double.NaN,
                    "retarder block singular; decomposition not uniquely defined");
        }
        Mat mDelta = m1.mul(invert4(mR));
        double ret = Math.acos(clamp((traceSub(mR) - 1) / 2, -1, 1));
        return new LCResult("M_delta*M_R*M_D", md, mR, mDelta, d, ret, null);
    }

    /** Reverse Lu-Chipman: M = M_D M_R M_delta, obtained via the transpose identity. */
    public static LCResult luChipmanReverse(Mat m) {
        LCResult f = luChipmanForward(m.transpose());
        if (f.diagnostic != null || f.depolarizer == null) {
            return new LCResult("M_D*M_R*M_delta", null, null, null,
                    f.diattenuation, Double.NaN, f.diagnostic);
        }
        // (A B C)^T = C^T B^T A^T: m^T = delta^T R^T D^T -> m = D R delta
        return new LCResult("M_D*M_R*M_delta",
                f.depolarizer.transpose(), f.retarder.transpose(), f.diattenuator.transpose(),
                f.diattenuation, f.retardanceRad, null);
    }

    private static Mat diattenuator(double d1, double d2, double d3, double d) {
        double eta = Math.sqrt(1 - d * d);
        Mat m = new Mat(4, 4);
        m.a[0][0] = 1;
        m.a[0][1] = d1; m.a[0][2] = d2; m.a[0][3] = d3;
        m.a[1][0] = d1; m.a[2][0] = d2; m.a[3][0] = d3;
        for (int i = 1; i < 4; i++) {
            double di = m.a[0][i];
            for (int j = 1; j < 4; j++) {
                double dj = m.a[0][j];
                m.a[i][j] = eta * (i == j ? 1 : 0) + (1 - eta) * di * dj / (d * d + 1e-30);
            }
        }
        return m;
    }

    private static Mat retarderFromM1(Mat m1) {
        double[][] sub = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) sub[i][j] = m1.a[i + 1][j + 1];
        double[][] s = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                s[i][j] = sub[i][j] - (i < 1 || j < 1 ? 0 : 0);
        // standard extraction: solve m_R^T m_R = ... via normalized sub-block
        double[][] rSub = normalizedOrthogonalPart(sub);
        if (rSub == null) return null;
        Mat r = Mat.identity(4);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r.a[i + 1][j + 1] = rSub[i][j];
        return r;
    }

    /** Nearest orthogonal matrix R to a 3x3 block via iterative polar iteration (Higham). */
    private static double[][] normalizedOrthogonalPart(double[][] a) {
        double[][] x = a;
        for (int it = 0; it < 60; it++) {
            double det = Linalg.det3(x);
            if (Math.abs(det) < 1e-12) return null;
            double[][] inv = inv3(x);
            double[][] y = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) y[i][j] = 0.5 * (x[i][j] + inv[j][i]);
            double diff = 0;
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) diff = Math.max(diff, Math.abs(y[i][j] - x[i][j]));
            x = y;
            if (diff < 1e-12) break;
        }
        if (Linalg.det3(x) < 0) return null; // proper rotation required
        return x;
    }

    private static double[][] inv3(double[][] a) {
        double det = Linalg.det3(a);
        if (Math.abs(det) < 1e-14) throw new IllegalArgumentException("singular 3x3");
        double[][] inv = new double[3][3];
        inv[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det;
        inv[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det;
        inv[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det;
        inv[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det;
        inv[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det;
        inv[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det;
        inv[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det;
        inv[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det;
        inv[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;
        return inv;
    }

    private static double traceSub(Mat r) {
        return r.a[1][1] + r.a[2][2] + r.a[3][3];
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Mat invert4(Mat m) {
        return Linalg.inverse(m);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4g", d);
    }
}
