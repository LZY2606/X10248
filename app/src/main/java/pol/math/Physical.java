package pol.math;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Physical realizability audit of a Mueller matrix.
 *
 * The Cloude coherency test is necessary and sufficient for a Mueller matrix
 * (given it maps the forward light cone correctly): the 4x4 Hermitian coherency
 * matrix H, built from Pauli-basis components, must be positive semidefinite.
 *
 * A non-physical candidate is FLAGGED with the violated constraints; it is never
 * silently projected and re-presented as the original solution.
 */
public final class Physical {

    // Pauli basis in block form matching Cloude's construction:
    //   S0=I2, S1=diag(1,-1), S2=[[0,1],[1,0]], S3=[[0,-i],[i,0]]
    private static final C[][] S0 = {{C.of(1), C.of(0)}, {C.of(0), C.of(1)}};
    private static final C[][] S1 = {{C.of(1), C.of(0)}, {C.of(0), C.of(-1)}};
    private static final C[][] S2 = {{C.of(0), C.of(1)}, {C.of(1), C.of(0)}};
    private static final C[][] S3 = {{C.of(0), new C(0, -1)}, {new C(0, 1), C.of(0)}};
    private static final C[][][] SIGMA = {S0, S1, S2, S3};

    public static final class Audit {
        public final boolean realizable;
        public final double[] coherencyEigenvalues;
        public final double minEigenvalue;
        public final List<Map<String, Object>> violations;
        public final Map<String, Double> residuals;
        public final double depolarizationIndex;

        Audit(boolean realizable, double[] ev, double min, List<Map<String, Object>> violations,
              Map<String, Double> residuals, double di) {
            this.realizable = realizable;
            this.coherencyEigenvalues = ev;
            this.minEigenvalue = min;
            this.violations = violations;
            this.residuals = residuals;
            this.depolarizationIndex = di;
        }
    }

    public static Audit audit(double[][] m) {
        // Mueller in SIQUV order. Build M_i via M = sum_ij m_ij (S_i otimes S_j)^*
        // H[alpha,beta] = 1/4 trace(Sigma_alpha M Sigma_beta M^dag)-style construction below;
        // we use the direct coefficient definition: H_{ij,kl} = 1/4 sum_{mu nu} ... — implemented
        // through coherency components H_{ij} = 1/4 sum_k trace(Sigma_i M Sigma_j ...). The
        // standard identity used here: H is the 4x4 matrix of coefficients of M in the
        // Pauli product basis with the conjugation rule m -> H given by Cloude 1986.
        // Cloude coherency: C = (1/4) sum_ij m_ij (sigma_i (x) sigma_j).
        C[][] Cmat = new C[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) Cmat[i][j] = C.of(0);
        for (int mi = 0; mi < 4; mi++) {
            for (int mj = 0; mj < 4; mj++) {
                C[][] K = kron(SIGMA[mi], SIGMA[mj]);
                double w = m[mi][mj] / 4.0;
                for (int i = 0; i < 4; i++)
                    for (int j = 0; j < 4; j++) Cmat[i][j] = Cmat[i][j].add(K[i][j].mul(w));
            }
        }
        C[][] H = Cmat;

        // Hermitian eigen-decomposition (real symmetric doubled matrix trick).
        double[] ev = hermEigenvalues(H);
        java.util.Arrays.sort(ev);

        List<Map<String, Object>> violations = new ArrayList<>();
        Map<String, Double> residuals = new LinkedHashMap<>();
        double minEv = ev[0];
        double tol = 1e-9;
        if (minEv < -tol) {
            violations.add(violation("CLOUDE_COHERENCY_PSD",
                    "coherency matrix has negative eigenvalue", minEv, 0.0));
        }
        residuals.put("coherencyMinEigenvalue", minEv);
        residuals.put("coherencyMaxEigenvalue", ev[3]);

        // Elementary necessary conditions with explicit residuals (all in normalized Mueller units).
        double passivity = Math.abs(m[0][0]) - 1.0 - tol;
        residuals.put("m00BoundExcess", Math.max(0, passivity));
        if (passivity > 0) violations.add(violation("M00_BOUND", "|m00| exceeds normalization bound", m[0][0], 1.0));

        double sub = 0;
        for (int i = 1; i < 4; i++)
            for (int j = 1; j < 4; j++) sub += m[i][j] * m[i][j];
        double diSq = sub / (m[0][0] * m[0][0]);
        double di = Math.sqrt(Math.max(0, diSq));
        residuals.put("depolarizationIndexExcess", Math.max(0, di - Math.sqrt(3) - tol));
        if (di > Math.sqrt(3) + tol)
            violations.add(violation("DEPOLARIZATION_INDEX", "depolarization index exceeds sqrt(3)", di, Math.sqrt(3)));

        // |m0j| <= m00 and |mi0| <= m00 for passive systems (signed residual)
        for (int j = 1; j < 4; j++) {
            double ex = Math.abs(m[0][j]) - Math.abs(m[0][0]) - tol;
            residuals.put("row0AbsBound_" + j, Math.max(0, ex));
            if (ex > 0) violations.add(violation("ROW0_BOUND_" + j, "|m0" + j + "| > |m00|", m[0][j], m[0][0]));
        }
        for (int i = 1; i < 4; i++) {
            double ex = Math.abs(m[i][0]) - Math.abs(m[0][0]) - tol;
            residuals.put("col0AbsBound_" + i, Math.max(0, ex));
            if (ex > 0) violations.add(violation("COL0_BOUND_" + i, "|m" + i + "0| > |m00|", m[i][0], m[0][0]));
        }

        boolean ok = violations.isEmpty() && minEv >= -1e-8;
        return new Audit(ok, ev, minEv, violations, residuals, di);
    }

    private static Map<String, Object> violation(String code, String msg, double actual, double bound) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("constraint", code);
        v.put("message", msg);
        v.put("actual", actual);
        v.put("bound", bound);
        v.put("excess", actual - bound);
        v.put("action", "FLAGGED_NOT_PROJECTED");
        return v;
    }

    // ---- complex 4x4 machinery (sigma_i otimes sigma_j basis is 4x4 over C2xC2) ----
    static C[][] kron(C[][] A, C[][] B) { // Kronecker of 2x2
        C[][] R = new C[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) R[i][j] = C.of(0);
        for (int ai = 0; ai < 2; ai++)
            for (int aj = 0; aj < 2; aj++)
                for (int bi = 0; bi < 2; bi++)
                    for (int bj = 0; bj < 2; bj++)
                        R[2 * ai + bi][2 * aj + bj] = A[ai][aj].mul(B[bi][bj]);
        return R;
    }

    static C[][] conj2(C[][] A) {
        C[][] R = new C[2][2];
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) R[i][j] = new C(A[i][j].re, -A[i][j].im);
        return R;
    }

    static C[][] product(C[][] A, C[][] B) {
        int n = A.length;
        C[][] R = new C[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) R[i][j] = C.of(0);
        for (int i = 0; i < n; i++)
            for (int k = 0; k < n; k++)
                for (int j = 0; j < n; j++) R[i][j] = R[i][j].add(A[i][k].mul(B[k][j]));
        return R;
    }

    static C trace(C[][] A) {
        C t = C.of(0);
        for (int i = 0; i < A.length; i++) t = t.add(A[i][i]);
        return t;
    }

    static C[][] muellerAsComplex(double[][] m) {
        C[][] R = new C[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) R[i][j] = C.of(m[i][j]);
        return R;
    }

    /** Eigenvalues of a Hermitian matrix via real doubling: [Re -Im; Im Re]. */
    static double[] hermEigenvalues(C[][] H) {
        int n = H.length;
        double[][] D = new double[2 * n][2 * n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                D[i][j] = H[i][j].re;
                D[n + i][n + j] = H[i][j].re;
                D[i][n + j] = -H[i][j].im;
                D[n + i][j] = H[i][j].im;
            }
        Matrix.JacobiEigen eig = Matrix.symmetricEigen(new Matrix(D), 1e-22);
        // doubled matrix repeats each eigenvalue twice
        List<Double> picked = new ArrayList<>();
        double[] all = eig.values;
        for (int i = 0; i < all.length; i++) {
            boolean dup = false;
            for (Double p : picked) if (Math.abs(p - all[i]) < 1e-9) dup = true;
            if (!dup) picked.add(all[i]);
        }
        // fallback if numerical spacing defeated dedupe: average pairs
        double[] out;
        if (picked.size() == n) {
            out = picked.stream().mapToDouble(Double::doubleValue).toArray();
        } else {
            out = new double[n];
            for (int i = 0; i < n; i++) out[i] = (all[2 * i] + all[2 * i + 1]) / 2;
        }
        return out;
    }

    public static Map<String, Object> serialize(Audit a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realizable", a.realizable);
        m.put("coherencyEigenvalues", java.util.Arrays.stream(a.coherencyEigenvalues).boxed().toList());
        m.put("minEigenvalue", a.minEigenvalue);
        m.put("depolarizationIndex", a.depolarizationIndex);
        m.put("violations", a.violations);
        Map<String, Object> res = new LinkedHashMap<>();
        a.residuals.forEach(res::put);
        m.put("residuals", res);
        return m;
    }
}
