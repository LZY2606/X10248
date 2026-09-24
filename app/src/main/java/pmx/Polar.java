package pmx;

import java.util.*;

import static pmx.LinAlg.*;

/**
 * Polarization domain: Stokes/Mueller conventions, measurement design matrix,
 * generalized least-squares Mueller estimate, Cloude physical realizability and
 * Lu-Chipman / Cloude decompositions.
 *
 * Conventions (all explicit and fingerprinted):
 *  - Stokes order given by order[]: canonical [I,Q,U,V]
 *  - rotationSign: +1 CCW looking INTO the beam; -1 flips every mechanical angle
 *  - intensity y_i = dark_i + (a_i)^T W M g_i, W instrument/calibration weight
 *  - vectorization m = (m00,m01,...,m03, m10,...,m33) row-major;
 *    design row X_i = kron(g_i, a_i)^T in that ordering.
 */
public final class Polar {
    private Polar() {}

    public static final String[] CANON = {"I", "Q", "U", "V"};

    /** A measured/encoded optical state with provenance-safe raw tokens. */
    public static final class State {
        public final int index;
        public final String label;
        public final String kind;       // gen: HWP,QWP,P,LP ; ana: LP,P,QWP
        public final String angleRaw;  // kept verbatim
        public final double angleDeg;  // radians after applying rotationSign, or NaN
        public final double[] sCanon;  // canonical-order Stokes, NaN entries if unparseable
        public final List<String> diagnostics = new ArrayList<>();
        public State(int index, String label, String kind, String angleRaw, double angleDeg, double[] s) {
            this.index = index; this.label = label; this.kind = kind;
            this.angleRaw = angleRaw; this.angleDeg = angleDeg; this.sCanon = s;
        }
    }

    public static final class Design {
        public final double[][] x;          // N x 16
        public final boolean[] rowValid;    // false where a state was unconvertible
        public final double cond;
        public final int rank;
        public final double[] singularValues;
        public final double[][] nullspace;  // columns (16), empty if full rank
        public final boolean singular;
        public final boolean nearSingular;
        public Design(double[][] x, boolean[] v, SVD svd, double[][] ns, boolean near) {
            this.x = x; this.rowValid = v; this.cond = svd.cond; this.rank = svd.rank;
            this.singularValues = svd.s; this.nullspace = ns;
            this.singular = svd.rank < 16; this.nearSingular = near;
        }
    }

    public static final class Estimate {
        public double[][] m;              // min-norm candidate; null if unusable
        public double[][] cov16;         // 16x16 covariance of vec(M)
        public double[] sigma;           // per-element std
        public Design design;
        public double chisq;
        public int dof;
        public double[] residuals;
        public boolean determinate;      // false == rank deficient
    }

    public static final class Physicality {
        public double[] coherencyEigen;  // ascending, length 4
        public double minEigen;
        public double minEigenRel;       // / m00
        public boolean psd;
        public double psdResidual;       // max(0,-minEigen)/m00
        public double rowDiattenuation;  // m00 - |first row tail|
        public double colDiattenuation;  // m00 - |first col tail|
        public boolean passive;
        public double passiveMargin;
        public double depolarization;    // Cloude depolarization index style
        public List<String> violations = new ArrayList<>();
    }

    // ---------------- Stokes encoding ----------------

    /** Parse an angle token; NaN preserved on failure with a diagnostic. */
    static double parseAngle(String raw, int rotationSign, List<String> diag) {
        if (raw == null || raw.isBlank()) { diag.add("missing angle"); return Double.NaN; }
        try {
            return rotationSign * Double.parseDouble(raw.trim()) * Math.PI / 180.0;
        } catch (NumberFormatException ex) {
            diag.add("unconvertible angle: '" + raw + "' kept at source, excluded from fit");
            return Double.NaN;
        }
    }

    /** Stokes vector (canonical order) for a generator/analyzer element. */
    public static double[] stokesFor(String kind, double theta, List<String> diag) {
        double[] s = new double[4];
        if (Double.isNaN(theta)) { Arrays.fill(s, Double.NaN); return s; }
        double c2 = Math.cos(2 * theta), s2 = Math.sin(2 * theta);
        double c4 = Math.cos(4 * theta), s4 = Math.sin(4 * theta);
        switch (kind.trim().toUpperCase(Locale.ROOT)) {
            case "P": case "LP": // ideal linear polarizer, transmission axis theta
                s[0] = 1; s[1] = c2; s[2] = s2; s[3] = 0;
                break;
            case "HWP": // half-wave plate + reference horizontal polarizer
                s[0] = 1; s[1] = c4; s[2] = s4; s[3] = 0;
                break;
            case "QWP": // quarter-wave plate then fixed horizontal polarizer
                s[0] = 1; s[1] = c2 * c2; s[2] = s2 * c2; s[3] = s2;
                break;
            case "V": // direct explicit vector
                s[0] = 1; break;
            default:
                diag.add("unknown optic kind '" + kind + "'");
                Arrays.fill(s, Double.NaN);
        }
        return s;
    }

    public static State parseState(int idx, String label, String kind, String angleRaw,
                                   int rotationSign) {
        List<String> diag = new ArrayList<>();
        double th = parseAngle(angleRaw, rotationSign, diag);
        double[] s = stokesFor(kind, th, diag);
        State st = new State(idx, label, kind, angleRaw, th, s);
        st.diagnostics.addAll(diag);
        return st;
    }

    /** Re-order a canonical Stokes vector into the declared acquisition order. */
    public static double[] reorderCanonical(double[] canon, String[] order) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            int p = List.of(CANON).indexOf(order[i].toUpperCase(Locale.ROOT));
            if (p < 0) p = i;
            out[i] = canon[p];
        }
        return out;
    }

    // ---------------- design matrix ----------------

    /**
     * Build the N x 16 design matrix X[ i, 4r+c ] = a_i[r] * g_i[c] (canonical order).
     * Rows using unconvertible states are retained but flagged invalid (they are
     * never silently dropped from the imported record).
     */
    public static Design buildDesign(List<State> gens, List<State> anas) {
        int n = gens.size();
        if (anas.size() != n) throw new IllegalArgumentException("generator/analyzer count mismatch");
        double[][] x = mat(n, 16);
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            double[] g = gens.get(i).sCanon, a = anas.get(i).sCanon;
            boolean ok = true;
            for (double v : g) if (Double.isNaN(v)) ok = false;
            for (double v : a) if (Double.isNaN(v)) ok = false;
            valid[i] = ok;
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    x[i][4 * r + c] = ok ? a[r] * g[c] : 0;
        }
        SVD svd = svd(x, 1e-10);
        double[][] ns = new double[16][Math.max(0, 16 - svd.rank)];
        for (int k = svd.rank; k < 16; k++)
            for (int i = 0; i < 16; i++) ns[i][k - svd.rank] = svd.v[i][k];
        double scale = Math.max(1, svd.smax);
        boolean near = svd.rank == 16 && svd.cond > 1e10;
        return new Design(x, valid, svd, ns, near);
    }

    // ---------------- estimate (GLS) ----------------

    /**
     * Weighted / generalized least squares. Covariance may be diagonal (sigma^2)
     * or dense (correlated errors). Near-singular designs are never force-inverted:
     * the minimum-norm SVD solution is returned together with the nullspace and a
     * determinate=false flag so all legal candidates remain explicit.
     */
    public static Estimate estimate(Design d, double[] y, double[][] cov) {
        int n = y.length;
        double[] wsqrt = new double[n];
        double[][] L = null;
        if (cov != null) {
            boolean diagonal = true;
            for (int i = 0; i < n && diagonal; i++)
                for (int j = 0; j < n; j++)
                    if (i != j && Math.abs(cov[i][j]) > 1e-15) { diagonal = false; break; }
            if (diagonal) {
                for (int i = 0; i < n; i++) wsqrt[i] = 1 / Math.sqrt(Math.max(cov[i][i], 1e-30));
            } else {
                try { L = cholesky(cov); }
                catch (SingularException ex) {
                    for (int i = 0; i < n; i++) wsqrt[i] = 1 / Math.sqrt(Math.max(cov[i][i], 1e-30));
                }
            }
        } else {
            for (int i = 0; i < n; i++) wsqrt[i] = 1;
        }
        // whiten: Xw = W X, yw = W y with W = L^{-1}
        double[][] xw = new double[n][16];
        double[] yw = new double[n];
        if (L != null) {
            for (int j = 0; j < 16; j++) {
                double[] col = new double[n];
                for (int i = 0; i < n; i++) col[i] = d.x[i][j];
                double[] wc = solveL(L, col);
                for (int i = 0; i < n; i++) xw[i][j] = wc[i];
            }
            double[] wy = solveL(L, y);
            System.arraycopy(wy, 0, yw, 0, n);
        } else {
            for (int i = 0; i < n; i++)
                for (int j = 0; j < 16; j++) xw[i][j] = d.x[i][j] * wsqrt[i];
            for (int i = 0; i < n; i++) yw[i] = y[i] * wsqrt[i];
        }
        SVD svd = svd(xw, 1e-10);
        double[] mvec = new double[16];
        // X^+ y = V S^-1 U^T y, only over non-null singular values
        for (int k = 0; k < svd.rank; k++) {
            double uty = 0;
            for (int i = 0; i < n; i++) uty += svd.u[i][k] * yw[i];
            double coef = uty / svd.s[k];
            for (int j = 0; j < 16; j++) mvec[j] += coef * svd.v[j][k];
        }
        // covariance of vec: (X^T W^T W X)^+ ; build from V, s
        double[][] cov16 = mat(16, 16);
        for (int k = 0; k < svd.rank; k++) {
            double inv2 = 1 / (svd.s[k] * svd.s[k]);
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 16; j++) cov16[i][j] += inv2 * svd.v[i][k] * svd.v[j][k];
        }
        double[] sigma = new double[16];
        for (int i = 0; i < 16; i++) sigma[i] = Math.sqrt(Math.max(cov16[i][i], 0));
        double[][] m = new double[4][4];
        for (int i = 0; i < 16; i++) m[i / 4][i % 4] = mvec[i];
        double[] res = new double[n];
        double chisq = 0;
        for (int i = 0; i < n; i++) {
            double pred = 0;
            for (int j = 0; j < 16; j++) pred += xw[i][j] * mvec[j];
            res[i] = yw[i] - pred;
            chisq += res[i] * res[i];
        }
        Estimate e = new Estimate();
        e.m = m; e.cov16 = cov16; e.sigma = sigma; e.design = d;
        e.chisq = chisq; e.dof = Math.max(0, n - svd.rank); e.residuals = res;
        e.determinate = d.rank == 16 && !d.nearSingular;
        return e;
    }

    public static double[][] vecToMat(double[] v) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 16; i++) m[i / 4][i % 4] = v[i];
        return m;
    }

    public static double[] matToVec(double[][] m) {
        double[] v = new double[16];
        for (int i = 0; i < 4; i++) System.arraycopy(m[i], 0, v, 4 * i, 4);
        return v;
    }

    // ---------------- Cloude coherency & physicality ----------------

    // Pauli basis (factor 1/2), indices 0..3
    static C[][] pauli(int p) {
        C z = C.r(0), o = C.r(1);
        C[][] m = new C[2][2];
        switch (p) {
            case 0: m[0][0] = o; m[0][1] = z; m[1][0] = z; m[1][1] = o; break;
            case 1: m[0][0] = o; m[0][1] = z; m[1][0] = z; m[1][1] = new C(-1, 0); break;
            case 2: m[0][0] = z; m[0][1] = o; m[1][0] = o; m[1][1] = z; break;
            default: m[0][0] = z; m[0][1] = new C(0, -1); m[1][0] = new C(0, 1); m[1][1] = z; break;
        }
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) m[i][j] = m[i][j].mul(new C(0.5, 0));
        return m;
    }

    static C[][] kron2(C[][] a, C[][] b) {
        C[][] c = new C[4][4];
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int u = 0; u < 2; u++)
                    for (int v = 0; v < 2; v++)
                        c[2 * i + u][2 * j + v] = a[i][j].mul(b[u][v]);
        return c;
    }


    // T[i*4+j] = sigma_i (x) sigma_j* with Pauli matrices normalized by 1/2.
    // H = sum_ij m_ij T_ij and m_ij = 4 Tr(T_ij H). Cached once.
    private static C[][][] PAULI_BASIS;
    static synchronized C[][] basis(int ij) {
        if (PAULI_BASIS == null) {
            PAULI_BASIS = new C[16][4][4];
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    PAULI_BASIS[i * 4 + j] = kron2(pauli(i), pauliConj(j));
        }
        return PAULI_BASIS[ij];
    }

    static C[][] pauliConj(int p) {
        if (p != 3) return pauli(p); // only sigma_2 is complex: sigma_2* = -sigma_2
        C[][] m = pauli(p), n = new C[2][2];
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++) n[i][j] = new C(-m[i][j].re, -m[i][j].im);
        return n;
    }

    // Cloude coherency matrix H = sum_ij m_ij (sigma_i/2 tensor sigma_j* /2).
    public static C[][] coherency(double[][] m) {
        C[][] h = new C[4][4];
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) h[a][b] = new C(0, 0);
        for (int ij = 0; ij < 16; ij++) {
            C coef = C.r(m[ij / 4][ij % 4]);
            C[][] p = basis(ij);
            for (int a = 0; a < 4; a++)
                for (int b = 0; b < 4; b++)
                    h[a][b] = h[a][b].add(coef.mul(p[a][b]));
        }
        return h;
    }

    public static Physicality assessPhysicality(double[][] m) {
        Physicality ph = new Physicality();
        C[][] h = coherency(m);
        ph.coherencyEigen = hermEig(h);
        ph.minEigen = ph.coherencyEigen[0];
        double m00 = Math.abs(m[0][0]) > 1e-30 ? Math.abs(m[0][0]) : 1e-30;
        ph.minEigenRel = ph.minEigen / m00;
        ph.psd = ph.minEigen >= -1e-8 * m00;
        ph.psdResidual = Math.max(0, -ph.minEigen) / m00;
        double rowTail = Math.hypot(Math.hypot(m[0][1], m[0][2]), m[0][3]);
        double colTail = Math.hypot(Math.hypot(m[1][0], m[2][0]), m[3][0]);
        ph.rowDiattenuation = m[0][0] - rowTail;
        ph.colDiattenuation = m[0][0] - colTail;
        ph.passive = m[0][0] > 0 && ph.rowDiattenuation >= -1e-8 && ph.colDiattenuation >= -1e-8;
        ph.passiveMargin = Math.min(ph.rowDiattenuation, ph.colDiattenuation);
        double sum = 0;
        for (double[] r : m) for (double x : r) sum += x * x;
        // depolarization index, 0 pure nondepolarizing -> 1 ideal depolarizer
        ph.depolarization = Math.sqrt(Math.max(0, sum - m[0][0] * m[0][0])) / (Math.sqrt(3) * m[0][0]);
        ph.depolarization = clamp(ph.depolarization, 0, 1);
        if (!ph.psd) ph.violations.add(String.format(Locale.ROOT,
                "Cloude coherency not PSD: min eigen=%.3e (%.2e of m00)", ph.minEigen, ph.minEigenRel));
        if (m[0][0] <= 0) ph.violations.add("m00 non-positive: " + fmt(m[0][0]));
        if (ph.rowDiattenuation < -1e-8) ph.violations.add("active (row diattenuation>1): margin=" + fmt(ph.rowDiattenuation));
        if (ph.colDiattenuation < -1e-8) ph.violations.add("column diattenuation>1: margin=" + fmt(ph.colDiattenuation));
        return ph;
    }

    // ---------------- decompositions ----------------

    public static final class CloudeComponent {
        public final double lambda;
        public final double[][] m;
        public CloudeComponent(double lambda, double[][] m) { this.lambda = lambda; this.m = m; }
    }

    /** Cloude spectral decomposition into up to 4 (rank-1) Mueller components. */
    public static List<CloudeComponent> cloudeSpectral(double[][] m) {
        C[][] h = coherency(m);
        int n = 4;
        double[][] b = mat(8, 8);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                b[i][j] = h[i][j].re; b[n + i][n + j] = h[i][j].re;
                b[i][n + j] = -h[i][j].im; b[n + i][j] = h[i][j].im;
            }
        SymEig e = symEig(b); // ascending, paired degeneracy
        List<CloudeComponent> out = new ArrayList<>();
        for (int pair = 0; pair < 4; pair++) {
            int col = 2 * pair; // first eigenvector of the degenerate pair
            double lambda = e.lambda[col];
            C[] z = new C[4];
            double norm = 0;
            // Kronecker eigenvector index is 2*row+col (transpose vs stacked block).
            for (int r = 0; r < 2; r++)
                for (int cc = 0; cc < 2; cc++) {
                    int k = 2 * r + cc;
                    z[k] = new C(e.v[2 * r + cc][col], e.v[4 + 2 * r + cc][col]);
                    norm += z[k].abs2();
                }
            double[][] mc = new double[4][4];
            if (norm > 1e-24)
                for (int a = 0; a < 4; a++)
                    for (int bb = 0; bb < 4; bb++) {
                        C[][] p = basis(a * 4 + bb);
                        C quad = new C(0, 0); // z^dag T z
                        for (int i = 0; i < 4; i++)
                            for (int j = 0; j < 4; j++)
                                quad = quad.add(z[i].conj().mul(p[i][j]).mul(z[j]));
                        mc[a][bb] = 4 * lambda * quad.re / norm;
                    }
            out.add(new CloudeComponent(lambda, mc));
        }
        Collections.reverse(out); // dominant first
        return out;
    }

    public static final class LuChipman {
        public double[][] mDiattenuator, mRetarder, mDepolarizer;
        public double diat, retardance, depolarizationPower;
        public boolean available;
        public String diagnostic;
    }

    static double[][] symSqrt3(double[][] a) {
        SymEig e = symEig(a);
        double[][] r = mat(3, 3);
        for (int k = 0; k < 3; k++) {
            double f = Math.sqrt(Math.max(e.lambda[k], 0));
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) r[i][j] += f * e.v[i][k] * e.v[j][k];
        }
        return r;
    }

    /** Lu-Chipman M = M_delta M_R M_D. Unavailable (with reason) for singular blocks. */
    public static LuChipman luChipman(double[][] m) {
        LuChipman lc = new LuChipman();
        try {
            double t = m[0][0];
            if (Math.abs(t) < 1e-12) { lc.available = false; lc.diagnostic = "m00 ~ 0, Lu-Chipman undefined"; return lc; }
            double[] d = {m[0][1] / t, m[0][2] / t, m[0][3] / t};
            double dn = Math.hypot(Math.hypot(d[0], d[1]), d[2]);
            lc.diat = dn;
            double k = dn > 1e-9 ? (Math.sqrt(Math.max(0, 1 - dn * dn)) - 1) / (dn * dn) : 0;
            double[][] md = mat(4, 4);
            md[0][0] = 1;
            for (int i = 0; i < 3; i++) { md[0][i + 1] = d[i]; md[i + 1][0] = d[i]; md[i + 1][i + 1] = 1 + k * d[i] * d[i]; }
            for (int i = 0; i < 3; i++)
                for (int j = i + 1; j < 3; j++) { md[i + 1][j + 1] = k * d[i] * d[j]; md[j + 1][i + 1] = md[i + 1][j + 1]; }
            double[][] mdInv = inverse(md);
            double[][] m1 = mul(m, mdInv); // first row (1,0,0,0)
            double[] pv = {m1[1][0], m1[2][0], m1[3][0]};
            double[][] low = mat(3, 3);
            for (int i = 0; i < 3; i++) System.arraycopy(m1[i + 1], 1, low[i], 0, 3);
            double[][] lowT = transpose(low);
            double[][] mDelta3 = symSqrt3(mul(low, lowT));
            double[][] mDelta3Inv = inverse(mDelta3);
            double[][] mR3 = mul(mDelta3Inv, low);
            // guard: if polar block ill-conditioned this is flagged, not faked
            double det = det3(mR3);
            if (Double.isNaN(det) || det <= 0) { lc.available = false; lc.diagnostic = "retarder block not SO(3), det=" + fmt(det); return lc; }
            double[][] mDelta = mat(4, 4); mDelta[0][0] = 1;
            System.arraycopy(pv, 0, mDelta[1], 0, 0);
            mDelta[1][0] = pv[0]; mDelta[2][0] = pv[1]; mDelta[3][0] = pv[2];
            for (int i = 0; i < 3; i++) System.arraycopy(mDelta3[i], 0, mDelta[i + 1], 1, 3);
            double[][] mR = mat(4, 4); mR[0][0] = 1;
            for (int i = 0; i < 3; i++) System.arraycopy(mR3[i], 0, mR[i + 1], 1, 3);
            lc.mDiattenuator = md; lc.mRetarder = mR; lc.mDepolarizer = mDelta;
            double c = (mR3[0][0] + mR3[1][1] + mR3[2][2] - 1) / 2.0;
            lc.retardance = Math.acos(clamp(c, -1, 1)) * 180 / Math.PI;
            double tr = mDelta3[0][0] + mDelta3[1][1] + mDelta3[2][2];
            lc.depolarizationPower = clamp(1 - Math.abs((tr - 1) / 3.0), 0, 1);
            lc.available = true;
            return lc;
        } catch (SingularException ex) {
            lc.available = false; lc.diagnostic = "singular block: " + ex.getMessage(); return lc;
        }
    }

    static double det3(double[][] a) {
        return a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
             - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
             + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    }
}
