package polar.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller-matrix estimation and review math.
 *
 * Rules enforced here:
 *  - a near-singular design matrix is never force-inverted: the estimate is
 *    reported as UNDECIDED with diagnostics instead;
 *  - a non-physical Mueller candidate is flagged with its violation residual
 *    and kept as-is; it is never projected back onto the physical cone and
 *    then presented as the original solution;
 *  - the rotation convention and Stokes order are explicit inputs of every
 *    derived quantity (and of its fingerprint).
 */
public final class MuellerCalc {
    private MuellerCalc() {}

    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_VIOLATION = "PHYSICALITY_VIOLATION";
    public static final String STATUS_UNDECIDED = "UNDECIDED_SINGULAR";

    public record Meas(String id, double[] g, double[] a, double y) {}

    public record Estimate(String status, double[] m, double[] errors,
                           double condition, int rank, double chi2, String note) {
        public boolean decided() { return m != null; }
    }

    /** Kronecker row a (x) g : intensity = row . vec(M). */
    public static double[] designRow(double[] a, double[] g) {
        double[] row = new double[16];
        int k = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) row[k++] = a[i] * g[j];
        return row;
    }

    /**
     * Weighted least squares m = (A'WA)^-1 A'W y.
     * @param w full weight matrix (inverse covariance), n x n
     */
    public static Estimate estimate(List<Meas> ms, double[][] w, double singularThreshold) {
        int n = ms.size();
        double[][] ata = new double[16][16];
        double[] aty = new double[16];
        for (int k = 0; k < n; k++) {
            double[] row = designRow(ms.get(k).a(), ms.get(k).g());
            for (int l = 0; l < n; l++) {
                double wkl = w[k][l];
                if (wkl == 0) continue;
                double[] rowL = designRow(ms.get(l).a(), ms.get(l).g());
                for (int i = 0; i < 16; i++) {
                    ata[i][0] += 0; // keep loop shape explicit
                    for (int j = 0; j < 16; j++) ata[i][j] += row[i] * wkl * rowL[j];
                }
                atyAdd(aty, row, wkl * ms.get(l).y());
            }
        }
        Linalg.Eigen eg = Linalg.eigenSym(ata);
        double lamMax = Math.abs(eg.values[15]);
        int rank = 0;
        for (double lam : eg.values) if (lam > lamMax * 1e-10) rank++;
        double cond = Linalg.condSym(eg.values);
        if (rank < 16 || cond > singularThreshold || Double.isInfinite(cond)) {
            return new Estimate(STATUS_UNDECIDED, null, null, cond, rank, Double.NaN,
                    "design matrix near-singular (rank=" + rank + ", cond=" + cond
                            + "); inversion refused");
        }
        double[][] ataInv = Linalg.inverseSym(ata, 1e-12);
        // aty currently holds A'W y
        double[] m = Linalg.mul(ataInv, aty);
        double[] errors = new double[16];
        for (int i = 0; i < 16; i++) errors[i] = Math.sqrt(Math.max(0.0, ataInv[i][i]));
        double chi2 = 0;
        for (int k = 0; k < n; k++) {
            double[] row = designRow(ms.get(k).a(), ms.get(k).g());
            double r = ms.get(k).y() - dot(row, m);
            for (int l = 0; l < n; l++) {
                double[] rowL = designRow(ms.get(l).a(), ms.get(l).g());
                chi2 += r * w[k][l] * (ms.get(l).y() - dot(rowL, m));
            }
        }
        return new Estimate(STATUS_VALID, m, errors, cond, rank, chi2, "ok");
    }

    private static void atyAdd(double[] aty, double[] row, double f) {
        for (int i = 0; i < 16; i++) aty[i] += row[i] * f;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** Pauli matrices sigma_0..sigma_3 as complex 2x2, stored re/im. */
    private static double[][][] pauliRe = {
            {{1, 0}, {0, 1}}, {{1, 0}, {0, -1}}, {{1, 0}, {0, 1}}, {{0, 0}, {0, 0}}};
    private static double[][][] pauliIm = {
            {{0, 0}, {0, 0}}, {{0, 0}, {0, 0}}, {{0, 0}, {0, 0}}, {{0, -1}, {1, 0}}};

    /** Smallest eigenvalue of the 4x4 coherency matrix H = 1/4 sum m_ij sigma_i (x) sigma_j. */
    public static double minCoherencyEigen(double[] m16) {
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int mu = 0; mu < 4; mu++) for (int nu = 0; nu < 4; nu++) {
            double coeff = m16[mu * 4 + nu] / 4.0;
            if (coeff == 0) continue;
            for (int p = 0; p < 2; p++) for (int q = 0; q < 2; q++)
                for (int r = 0; r < 2; r++) for (int s = 0; s < 2; s++) {
                    re[p * 2 + r][q * 2 + s] += coeff * (pauliRe[mu][p][q] * pauliRe[nu][r][s]
                            - pauliIm[mu][p][q] * pauliIm[nu][r][s]);
                    im[p * 2 + r][q * 2 + s] += coeff * (pauliRe[mu][p][q] * pauliIm[nu][r][s]
                            + pauliIm[mu][p][q] * pauliRe[nu][r][s]);
                }
        }
        // Hermitian 4x4 -> real symmetric 8x8 [[Re,-Im],[Im,Re]], eigenvalues doubled
        double[][] big = new double[8][8];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            big[i][j] = re[i][j];
            big[i][4 + j] = -im[i][j];
            big[4 + i][j] = im[i][j];
            big[4 + i][4 + j] = re[i][j];
        }
        double[] vals = Linalg.eigenSym(big).values;
        return vals[0];
    }

    /** Physicality residual: min coherency eigenvalue; negative means violation. */
    public static String physicalityStatus(double[] m16, double tol) {
        return minCoherencyEigen(m16) >= -tol ? STATUS_VALID : STATUS_VIOLATION;
    }

    // ------------------------------------------------------------------
    // Conventions
    // ------------------------------------------------------------------

    /** Rotation matrix R(theta) for Stokes (I,Q,U,V). */
    public static double[][] rotation(double thetaRad, String convention) {
        double c = Math.cos(2 * thetaRad), s = Math.sin(2 * thetaRad);
        if ("PASSIVE_CW".equals(convention)) s = -s;
        return new double[][]{
                {1, 0, 0, 0},
                {0, c, s, 0},
                {0, -s, c, 0},
                {0, 0, 0, 1}};
    }

    /** Mueller matrix of a quarter-wave plate, fast axis horizontal. */
    public static double[][] qwp() {
        return new double[][]{
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 0, 1},
                {0, 0, -1, 0}};
    }

    /** Mueller matrix of a QWP whose fast axis is rotated by theta. */
    public static double[][] qwp(double thetaRad, String convention) {
        double[][] r = rotation(thetaRad, convention);
        double[][] rInv = rotation(-thetaRad, convention);
        return Linalg.mul(Linalg.mul(rInv, qwp()), r);
    }

    /**
     * Permutation taking a Stokes vector declared in {@code declaredOrder}
     * (e.g. "S0S1S2S3" or "S0S2S1S3") into canonical S0S1S2S3 order.
     */
    public static double[] toCanonicalOrder(double[] s, String declaredOrder) {
        int[] perm = orderPermutation(declaredOrder);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = s[perm[i]];
        return out;
    }

    /** 4x4 permutation matrix P such that canonical = P * declared. */
    public static double[][] orderPermutationMatrix(String declaredOrder) {
        int[] perm = orderPermutation(declaredOrder);
        double[][] p = new double[4][4];
        for (int i = 0; i < 4; i++) p[i][perm[i]] = 1.0;
        return p;
    }

    private static int[] orderPermutation(String declaredOrder) {
        // tokens S0..S3 in declared order; canonical position of token Sk is k
        int[] perm = new int[4];
        for (int i = 0; i < 4; i++) {
            String tok = "S" + i;
            int idx = indexOfToken(declaredOrder, tok);
            if (idx < 0) throw new IllegalArgumentException("bad Stokes order: " + declaredOrder);
            perm[idx] = i; // canonical position idx takes declared component i
        }
        return perm;
    }

    private static int indexOfToken(String order, String tok) {
        int pos = 0;
        for (int i = 0; i + 1 < order.length(); i += 2) {
            if (order.substring(i, i + 2).equals(tok)) return pos;
            pos++;
        }
        return -1;
    }

    /** Permute a Mueller matrix into canonical order: M' = P M P^T. */
    public static double[] muellerToCanonical(double[] m16, String declaredOrder) {
        double[][] p = orderPermutationMatrix(declaredOrder);
        double[][] m = toMatrix(m16);
        double[][] t = Linalg.mul(Linalg.mul(p, m), Linalg.transpose(p));
        return toVec(t);
    }

    public static double[][] toMatrix(double[] m16) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) System.arraycopy(m16, i * 4, m[i], 0, 4);
        return m;
    }

    public static double[] toVec(double[][] m) {
        double[] v = new double[16];
        for (int i = 0; i < 4; i++) System.arraycopy(m[i], 0, v, i * 4, 4);
        return v;
    }

    // ------------------------------------------------------------------
    // Decomposition candidates (Lu-Chipman family)
    // ------------------------------------------------------------------

    public record Decomp(String kind, boolean valid, double diattenuation,
                         double retardance, double depolarizationIndex, String note) {}

    public static List<Decomp> decompose(double[] m16) {
        List<Decomp> out = new ArrayList<>();
        out.add(luChipman(m16, "DECOMP_LU_CHIPMAN"));
        out.add(reverse(m16));
        return out;
    }

    private static Decomp luChipman(double[] m16, String kind) {
        double[][] m = toMatrix(m16);
        double m00 = m[0][0];
        if (m00 <= 0) return new Decomp(kind, false, 0, 0, 0, "m00<=0, decomposition undefined");
        double[] d = {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
        double dn = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (dn >= 1.0 - 1e-12)
            return new Decomp(kind, false, dn, 0, 0, "|D|>=1: diattenuator not invertible");
        double[][] md = diattenuator(d, dn);
        double[][] mdInv = Linalg.inverseSym(md, 1e-12);
        double[][] mp = Linalg.mul(m, mdInv);
        double depol = depolIndex(mp);
        double retard = retardance(mp);
        return new Decomp(kind, true, dn, retard, depol, "M = M_dep M_ret M_dia");
    }

    private static Decomp reverse(double[] m16) {
        // Reverse product order: decompose M^T, whose diattenuation is M's polarizance.
        double[][] m = toMatrix(m16);
        double[][] mt = Linalg.transpose(m);
        Decomp t = luChipman(Linalg.toVec(mt), "DECOMP_REVERSE");
        return new Decomp("DECOMP_REVERSE", t.valid(), t.diattenuation(), t.retardance(),
                t.depolarizationIndex(), t.valid() ? "M = M_dia' M_ret' M_dep'" : t.note());
    }

    private static double[][] diattenuator(double[] d, double dn) {
        double sq = Math.sqrt(1 - dn * dn);
        double[][] md = new double[4][4];
        md[0][0] = 1;
        for (int i = 0; i < 3; i++) { md[0][i + 1] = d[i]; md[i + 1][0] = d[i]; }
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double v = (i == j ? sq : 0);
            if (dn > 1e-12) v += (1 - sq) * d[i] * d[j] / (dn * dn);
            md[i + 1][j + 1] = v;
        }
        return md;
    }

    private static double depolIndex(double[][] mp) {
        // polarization index P in [0,1]; depolarization index = 1 - P
        double m00 = mp[0][0];
        double sum = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) sum += mp[i][j] * mp[i][j];
        double p2 = (sum - m00 * m00) / (3 * m00 * m00);
        double p = Math.sqrt(Math.max(0.0, Math.min(1.0, p2)));
        return 1.0 - p;
    }

    private static double retardance(double[][] mp) {
        double m00 = mp[0][0];
        double tr = (mp[1][1] + mp[2][2] + mp[3][3]) / m00;
        double c = (tr - 1) / 2.0;
        return Math.acos(Math.max(-1.0, Math.min(1.0, c)));
    }
}
