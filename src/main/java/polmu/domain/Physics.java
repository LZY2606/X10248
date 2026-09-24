package polmu.domain;

import java.util.ArrayList;
import java.util.List;
import polmu.linalg.Lin;

/**
 * Physical realizability and decomposition for Mueller matrices.
 * Rule: a non-physical candidate is flagged with its violations and kept as-is;
 * it is never projected onto the physical set and passed off as the original solution.
 */
public final class Physics {
    public static final double PHYSICAL_TOLERANCE = 1e-9;

    private Physics() {}

    /** Hermitian coherency matrix H = 1/4 sum_ij M_ij (sigma_i (x) sigma_j*), returned as [re,im] pairs. */
    public static double[][][] coherency(double[][] m) {
        // Pauli matrices (canonical IQUV basis): s0=I, s1, s2, s3.
        double[][][] re = new double[4][2][2];
        double[][][] im = new double[4][2][2];
        re[0] = new double[][]{{1, 0}, {0, 1}};
        re[1] = new double[][]{{1, 0}, {0, -1}};
        re[2] = new double[][]{{0, 1}, {1, 0}};
        im[2] = new double[][]{{0, 0}, {0, 0}};
        re[3] = new double[][]{{0, 0}, {0, 0}};
        im[3] = new double[][]{{0, -1}, {1, 0}};
        double[][] hRe = new double[4][4];
        double[][] hIm = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double mij = m[i][j] / 4.0;
                // kron(sigma_i, conj(sigma_j))
                for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
                    for (int c = 0; c < 2; c++) for (int d = 0; d < 2; d++) {
                        double xr = re[i][a][b], xi = im[i][a][b];
                        double yr = re[j][c][d], yi = -im[j][c][d]; // conjugate
                        hRe[2 * a + c][2 * b + d] += mij * (xr * yr - xi * yi);
                        hIm[2 * a + c][2 * b + d] += mij * (xr * yi + xi * yr);
                    }
            }
        return new double[][][]{hRe, hIm};
    }

    /** Eigenvalues of the Hermitian coherency matrix, ascending. */
    public static double[] coherencyEigenvalues(double[][] m) {
        double[][][] h = coherency(m);
        // Embed Hermitian H = Re + i Im as real symmetric 8x8 [[Re, -Im],[Im, Re]] (spectrum doubles).
        double[][] emb = new double[8][8];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            emb[i][j] = h[0][i][j];
            emb[i][4 + j] = -h[1][i][j];
            emb[4 + i][j] = h[1][i][j];
            emb[4 + i][4 + j] = h[0][i][j];
        }
        double[] ev = Lin.eigenvaluesSymmetric(emb);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = ev[2 * i]; // each eigenvalue appears twice
        return out;
    }

    /** Physical constraint residuals: negative coherency eigenvalues below -tol. */
    public static List<String> physicalViolations(double[][] m) {
        double[] ev = coherencyEigenvalues(m);
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < ev.length; i++)
            if (ev[i] < -PHYSICAL_TOLERANCE)
                violations.add("coherency_eigenvalue[" + i + "]=" + Lin.fmt(ev[i]) + " < 0");
        return violations;
    }

    public static boolean isPhysical(double[][] m) {
        return physicalViolations(m).isEmpty();
    }

    /** Lu-Chipman decomposition: M = M_dep * M_ret * M_dia. Returns parameters, not a projection. */
    public static final class Decomposition {
        public String method;
        public double[] diattenuationVector = new double[3];
        public double diattenuation;
        public double[] polarizanceVector = new double[3];
        public double retardanceRadians;
        public double depolarizationIndex;
        public double[][] mDia, mRet, mDep;
    }

    public static Decomposition luChipman(double[][] m) {
        Decomposition d = new Decomposition();
        d.method = "lu-chipman:M_dep*M_ret*M_dia";
        double m00 = m[0][0];
        double[] dv = {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
        d.diattenuationVector = dv;
        double dmag = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1] + dv[2] * dv[2]);
        d.diattenuation = dmag;
        double p = Math.sqrt(Math.max(0, 1 - dmag * dmag));
        // M_dia = [[1, d^T],[d, mD]], mD = p I + (1-p) d^ d^T
        double[][] mDia = new double[4][4];
        mDia[0][0] = 1;
        for (int i = 0; i < 3; i++) { mDia[0][i + 1] = dv[i]; mDia[i + 1][0] = dv[i]; }
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double dhati = dmag > 1e-12 ? dv[i] / dmag : 0;
            double dhatj = dmag > 1e-12 ? dv[j] / dmag : 0;
            mDia[i + 1][j + 1] = p * (i == j ? 1 : 0) + (1 - p) * dhati * dhatj;
        }
        d.mDia = mDia;
        double[][] mDiaInv = invertDiattenuator(dv, p);
        double[][] mPrime = Lin.mul(m, mDiaInv);
        for (int i = 0; i < 3; i++) d.polarizanceVector[i] = mPrime[i + 1][0] / m00;
        // Depolarizer submatrix: mDep = a I + b p^ p^T from polarizance of mPrime
        double[] pv = d.polarizanceVector;
        double pmag = Math.sqrt(pv[0] * pv[0] + pv[1] * pv[1] + pv[2] * pv[2]);
        double a = Math.sqrt(Math.max(0, 1 - pmag * pmag));
        double[][] mDepSub = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double phi = pmag > 1e-12 ? pv[i] / pmag : 0;
            double phj = pmag > 1e-12 ? pv[j] / pmag : 0;
            mDepSub[i][j] = a * (i == j ? 1 : 0) + (1 - a) * phi * phj;
        }
        double[][] mDepSubInv = invert3(mDepSub);
        double[][] mRetSub = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double s = 0;
            for (int k = 0; k < 3; k++) s += mDepSubInv[i][k] * mPrime[k + 1][j + 1];
            mRetSub[i][j] = s;
        }
        double trace = mRetSub[0][0] + mRetSub[1][1] + mRetSub[2][2];
        d.retardanceRadians = Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2.0)));
        // Depolarization index from singular spread of mPrime submatrix
        double[][] mp = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) mp[i][j] = mPrime[i + 1][j + 1];
        double[] sv = Lin.eigenvaluesSymmetric(Lin.mul(Lin.transpose(mp), mp));
        double sum = 0;
        for (double v : sv) sum += Math.sqrt(Math.max(0, v));
        d.depolarizationIndex = 1 - sum / 3.0;
        d.mRet = new double[][]{{1, 0, 0, 0}, {0, mRetSub[0][0], mRetSub[0][1], mRetSub[0][2]},
                {0, mRetSub[1][0], mRetSub[1][1], mRetSub[1][2]}, {0, mRetSub[2][0], mRetSub[2][1], mRetSub[2][2]}};
        d.mDep = mPrime; // M_dep * M_ret combined residual recorded for audit
        return d;
    }

    /** Reverse-order decomposition candidate: M = M_dia' * M_ret' * M_dep' (product order swapped). */
    public static Decomposition reverseOrder(double[][] m) {
        // Decompose the transpose-like reversed product by decomposing M^T and transposing factors.
        double[][] mt = Lin.transpose(m);
        Decomposition t = luChipman(mt);
        Decomposition d = new Decomposition();
        d.method = "reverse-order:M_dia*M_ret*M_dep";
        d.diattenuationVector = t.polarizanceVector.clone();
        d.diattenuation = t.diattenuation;
        d.polarizanceVector = t.diattenuationVector.clone();
        d.retardanceRadians = t.retardanceRadians;
        d.depolarizationIndex = t.depolarizationIndex;
        d.mDia = t.mDia;
        d.mRet = t.mRet;
        d.mDep = t.mDep;
        return d;
    }

    private static double[][] invertDiattenuator(double[] dv, double p) {
        double[][] inv = new double[4][4];
        double d2 = 1 - p * p;
        inv[0][0] = 1.0 / (p * p);
        for (int i = 0; i < 3; i++) {
            inv[0][i + 1] = -dv[i] / (p * p);
            inv[i + 1][0] = -dv[i] / (p * p);
        }
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double dhati = d2 > 1e-12 ? dv[i] / Math.sqrt(d2) : 0;
            double dhatj = d2 > 1e-12 ? dv[j] / Math.sqrt(d2) : 0;
            inv[i + 1][j + 1] = (1.0 / p) * (i == j ? 1 : 0)
                    + (1 - 1.0 / p) * dhati * dhatj + dv[i] * dv[j] / (p * p);
        }
        return inv;
    }

    private static double[][] invert3(double[][] m) {
        double[][] out = new double[3][3];
        for (int j = 0; j < 3; j++) {
            double[] e = new double[3];
            e[j] = 1;
            double[] c = Lin.solve(m, e);
            for (int i = 0; i < 3; i++) out[i][j] = c[i];
        }
        return out;
    }
}
