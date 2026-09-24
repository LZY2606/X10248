package pol;

import java.util.*;

/**
 * Mueller estimation and physical-realizability review.
 *
 * Rules enforced here (mirrored as machine-readable events by the store layer):
 *  - A near-singular design matrix is NEVER forcibly inverted: estimation is refused
 *    and a diagnostic verdict is returned instead.
 *  - A non-physical Mueller candidate is FLAGGED with its Cloude-coherency
 *    violations; it is never silently projected onto the physical cone and then
 *    presented as the original solution.
 *  - Rotation convention and Stokes order are explicit parameters that enter the
 *    derivation fingerprint (handled by Fingerprint).
 */
public final class MuellerMath {
    private MuellerMath() {}

    /** Condition number above which the design matrix is treated as near-singular. */
    public static final double COND_LIMIT = 1e8;
    /** Tolerance (relative to m00) for Cloude coherency eigenvalue negativity. */
    public static final double PHYS_TOL = 1e-6;

    public enum StokesOrder { IQUV, IQVU }
    public enum RotationConvention { ACTIVE_CCW, PASSIVE_CW }

    /** One measurement: analyzer Stokes a, generator Stokes g, measured intensity. */
    public record Channel(double[] a, double[] g, double intensity) {}

    /** Build the 16-column design matrix W, row k = kron(a_k, g_k). */
    public static double[][] designMatrix(List<Channel> ch) {
        double[][] w = new double[ch.size()][16];
        for (int k = 0; k < ch.size(); k++) {
            double[] a = ch.get(k).a, g = ch.get(k).g;
            int c = 0;
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    w[k][c++] = a[i] * g[j];
        }
        return w;
    }

    public record Estimate(
            boolean refused,
            String verdict,          // "ok" | "near_singular_design"
            double conditionNumber,
            double[][] mueller,      // null when refused
            double[][] covariance,   // 16x16 covariance of vec(M), null when refused
            double[] residuals       // fit residuals i - W m, null when refused
    ) {}

    /**
     * Least-squares Mueller estimate. Refuses (without inverting) when the design
     * matrix is near-singular.
     */
    public static Estimate estimate(List<Channel> ch, double[][] intensityCov) {
        double[][] w = designMatrix(ch);
        double cond = Linalg.conditionNumber(w);
        if (!(cond < COND_LIMIT)) {
            return new Estimate(true, "near_singular_design", cond, null, null, null);
        }
        double[][] wt = Linalg.transpose(w);
        double[][] wtw = Linalg.mul(wt, w);
        double[][] wtwInv;
        try {
            wtwInv = Linalg.inverse(wtw);
        } catch (Linalg.SingularException e) {
            return new Estimate(true, "near_singular_design", Double.POSITIVE_INFINITY, null, null, null);
        }
        double[] i = new double[ch.size()];
        for (int k = 0; k < ch.size(); k++) i[k] = ch.get(k).intensity;
        double[] m = Linalg.mulVec(Linalg.mul(wtwInv, wt), i);
        double[][] mueller = new double[4][4];
        for (int r = 0; r < 4; r++) System.arraycopy(m, r * 4, mueller[r], 0, 4);
        // cov(m) = (WtW)^-1 Wt C W (WtW)^-1  (correlated errors supported)
        double[][] c = intensityCov != null ? intensityCov
                : scaleIdentity(ch.size(), 1.0);
        double[][] cov = Linalg.mul(Linalg.mul(Linalg.mul(wtwInv, wt), c), Linalg.mul(w, wtwInv));
        double[] fit = Linalg.mulVec(w, m);
        double[] res = new double[ch.size()];
        for (int k = 0; k < ch.size(); k++) res[k] = i[k] - fit[k];
        return new Estimate(false, "ok", cond, mueller, cov, res);
    }

    private static double[][] scaleIdentity(int n, double s) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = s;
        return m;
    }

    /** Real-symmetric 8x8 form of the Hermitian Cloude coherency matrix. */
    public static double[][] coherency(double[][] m) {
        double m00=m[0][0],m01=m[0][1],m02=m[0][2],m03=m[0][3];
        double m10=m[1][0],m11=m[1][1],m12=m[1][2],m13=m[1][3];
        double m20=m[2][0],m21=m[2][1],m22=m[2][2],m23=m[2][3];
        double m30=m[3][0],m31=m[3][1],m32=m[3][2],m33=m[3][3];
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        re[0][0]=(m00+m11+m22+m33)/2;
        re[0][1]=(m01+m10)/2; re[1][0]=re[0][1];
        re[0][2]=(m02+m20)/2; re[2][0]=re[0][2];
        re[0][3]=(m03+m30)/2; re[3][0]=re[0][3];
        re[1][1]=(m00+m11-m22-m33)/2;
        re[1][2]=(m12+m21)/2; re[2][1]=re[1][2];
        re[1][3]=(m13+m31)/2; re[3][1]=re[1][3];
        re[2][2]=(m00-m11+m22-m33)/2;
        re[2][3]=(m23+m32)/2; re[3][2]=re[2][3];
        re[3][3]=(m00-m11-m22+m33)/2;
        im[1][2]=(m12-m21)/2; im[2][1]=-im[1][2];
        im[1][3]=(m13-m31)/2; im[3][1]=-im[1][3];
        im[2][3]=(m23-m32)/2; im[3][2]=-im[2][3];
        double[][] big = new double[8][8];
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++) {
                big[r][c] = re[r][c];
                big[r][c + 4] = -im[r][c];
                big[r + 4][c] = im[r][c];
                big[r + 4][c + 4] = re[r][c];
            }
        return big;
    }

    public record Physicality(
            boolean physical,
            double minEigenvalue,
            double[] eigenvalues,
            List<String> violations
    ) {}

    /**
     * Physical realizability via Cloude coherency eigenvalues of the Hermitian
     * coherency matrix. For a real Mueller matrix the coherency matrix H is
     * Hermitian; its eigenvalues equal those of the real symmetric matrix
     * Re(H) ⊕ blocks — we assemble the full Hermitian matrix and compute
     * eigenvalues of the equivalent real symmetric 4x4 formed by the standard
     * explicit formulas including imaginary parts folded as antisymmetric terms.
     */
    public static Physicality physicality(double[][] m) {
        double[] all = Linalg.jacobiEigen(coherency(m), null);
        double[] w = new double[4];
        for (int i = 0; i < 4; i++) w[i] = all[2 * i];
        double min = w[0];
        double tol = PHYS_TOL * Math.max(1.0, Math.abs(m[0][0]));
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            if (w[i] < -tol)
                violations.add("coherency_eigenvalue_" + i + "=" + Linalg.fmt(w[i]) + " < 0");
        return new Physicality(violations.isEmpty(), min, w, violations);
    }

    /** Lu-Chipman style scalar summaries used as decomposition candidates. */
    public record Decomposition(
            String kind,                 // "lu_chipman_forward" | "lu_chipman_reverse"
            double diattenuation,
            double polarizance,
            double retardanceHint,
            double depolarizationIndex,
            boolean physical,
            List<String> notes
    ) {}

    /** Produce all legitimate decomposition candidates (order is not unique). */
    public static List<Decomposition> decompose(double[][] m) {
        Physicality ph = physicality(m);
        double m00 = m[0][0];
        double d = Math.hypot(m[0][1], Math.hypot(m[0][2], m[0][3])) / m00;
        double p = Math.hypot(m[1][0], Math.hypot(m[2][0], m[3][0])) / m00;
        double sumSq = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) sumSq += m[i][j] * m[i][j];
        double delta = Math.sqrt(Math.max(0, sumSq / (m00 * m00) - 1.0) / 3.0);
        // retardance hint from the 3x3 submatrix trace
        double tr = m[1][1] + m[2][2] + m[3][3];
        double cosR = Math.max(-1, Math.min(1, tr / 3.0 / Math.max(1e-12, 1 - delta)));
        double retard = Math.acos(cosR);
        List<String> notes = new ArrayList<>();
        if (!ph.physical) notes.add("nonphysical_source_matrix_not_projected");
        List<Decomposition> out = new ArrayList<>();
        out.add(new Decomposition("lu_chipman_forward", d, p, retard, delta, ph.physical,
                notes.isEmpty() ? List.of("M = M_dep * M_ret * M_dia") : notes));
        out.add(new Decomposition("lu_chipman_reverse", d, p, retard, delta, ph.physical,
                notes.isEmpty() ? List.of("M = M_dia * M_ret * M_dep") : notes));
        return out;
    }

    /** Permute/sign-flip a Mueller matrix for a Stokes-order change (IQUV <-> IQVU swaps V/U). */
    public static double[][] reorderStokes(double[][] m, StokesOrder from, StokesOrder to) {
        if (from == to) return m;
        int[] perm = {0, 1, 3, 2}; // swap U and V
        double[][] out = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                out[i][j] = m[perm[i]][perm[j]];
        return out;
    }

    /** Flip rotation-convention signs: elements coupling to the circular channel change sign. */
    public static double[][] flipRotationSign(double[][] m) {
        double[][] out = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                boolean flip = (i == 3) != (j == 3);
                out[i][j] = flip ? -m[i][j] : m[i][j];
            }
        return out;
    }
}
