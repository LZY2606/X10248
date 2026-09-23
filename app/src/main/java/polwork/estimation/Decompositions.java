package polwork.estimation;

import org.ejml.data.DMatrixRMaj;
import polwork.domain.DecompositionCandidate;

import java.util.Locale;

/**
 * Polar decompositions. Forward and reverse-order Lu-Chipman candidates are
 * both retained (the order ambiguity is resolved by the reviewer, never hidden),
 * together with the Cloude spectral components.
 */
public final class Decompositions {

    private Decompositions() {}

    public static DecompositionCandidate luChipmanForward(double[][] m) {
        return luChipman("luchipman_forward", m, false);
    }

    public static DecompositionCandidate luChipmanReverse(double[][] m) {
        // reverse order via transpose: M^T = D R Dp  =>  M = D^T R^T Dp^T
        double[][] t = transpose(m);
        DecompositionCandidate f = luChipman("luchipman_reverse", t, true);
        return new DecompositionCandidate("luchipman_reverse",
                transpose(f.diattenuator), transpose(f.retarder),
                transpose(f.depolarizer), f.retardanceRad, f.diattenuation,
                f.diagnostic, f.unique);
    }

    /**
     * M = M_depol M_ret M_diat (Lu-Chipman 1996).
     */
    private static DecompositionCandidate luChipman(String name, double[][] m, boolean transposed) {
        double m00 = m[0][0];
        if (Math.abs(m00) < 1e-15) {
            return new DecompositionCandidate(name, identity4(), identity4(), identity4(),
                    0, 0, "m00=0: decomposition undefined", false);
        }

        // diattenuation
        double[] d = {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
        double D = Math.min(0.999999, Math.sqrt(dot(d, d)));
        double[][] md = diattenuatorMatrix(d, D);

        // m1 = M MD^{-1}
        double[][] mdInv = diattenuatorMatrix(d, -D);
        double[][] m1 = mul4(m, mdInv);

        double[] p = {m1[1][0], m1[2][0], m1[3][0]};
        double[][] sub = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) sub[i][j] = m1[i + 1][j + 1];

        double det3 = det3(sub);
        String diag = "";
        if (det3 <= 0) {
            diag = "det(m')=" + fmt(det3) + "<=0: non-physical/noise solution, factors non-unique";
        }
        double delta = Math.sqrt(Math.max(1e-12, det3));

        // retardance from u = m'/delta
        double[][] u = scale(sub, 1.0 / delta);
        double tr = u[0][0] + u[1][1] + u[2][2];
        double cosR = clamp((tr - 1.0) / 2.0, -1, 1);
        double r = Math.acos(cosR);
        double sinR = Math.sin(r);

        double[][] mr = identity4();
        if (sinR > 1e-9) {
            double[] rv = {
                    (u[2][1] - u[1][2]) / (2 * sinR),
                    (u[0][2] - u[2][0]) / (2 * sinR),
                    (u[1][0] - u[0][1]) / (2 * sinR)
            };
            double n = Math.sqrt(dot(rv, rv));
            if (n > 1e-12) rv = scaleV(rv, 1.0 / n);
            double[][] rsub = axisRotation(rv, r);
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) mr[i + 1][j + 1] = rsub[i][j];
        }

        double[][] mrInv;
        // orthogonal retarder inverse: transpose of 3x3 block
        mrInv = identity4();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) mrInv[i + 1][j + 1] = mr[j + 1][i + 1];

        double[][] mdep = mul4(m1, mrInv);

        return new DecompositionCandidate(name, md, mr, mdep, r, D, diag, det3 > 0);
    }

    /** Cloude spectral components M_i = lambda_i v v^d in Pauli space. */
    public static DecompositionCandidate cloudeSpectral(double[][] m) {
        DMatrixRMaj h = PhysicalRealizability.coherency(m);
        var eig = org.ejml.dense.row.factory.DecompositionFactory_DDRM.eig(4, true, true);
        eig.decompose(h);
        double[] lambda = new double[4];
        double[][] vecs = new double[4][4];
        for (int i = 0; i < 4; i++) {
            lambda[i] = eig.getEigenvalue(i).real;
            DMatrixRMaj v = eig.getEigenVector(i);
            for (int k = 0; k < 4; k++) vecs[i][k] = v == null ? 0 : v.get(k);
        }
        boolean allNonneg = true;
        for (double l : lambda) if (l < -1e-9) allNonneg = false;
        String diag = String.format(Locale.ROOT,
                "cloude_eigenvalues=[%.5g,%.5g,%.5g,%.5g];%s",
                lambda[0], lambda[1], lambda[2], lambda[3],
                allNonneg ? "all_components_realizable" : "negative_component:parent_not_physical");
        // dominant component as the nominal factor triplet placeholder
        int dom = 0;
        for (int i = 1; i < 4; i++) if (lambda[i] > lambda[dom]) dom = i;
        double[][] mDom = pauliExpand(scaleV(vecs[dom], lambda[dom]), vecs[dom]);
        return new DecompositionCandidate("cloude_spectral",
                identity4(), identity4(), mDom, 0, 0, diag, allNonneg);
    }

    private static double[][] pauliExpand(double[] a, double[] bConj) {
        double[][][] sig = {
                {{1, 0}, {0, 1}}, {{1, 0}, {0, -1}},
                {{0, 1}, {1, 0}}, {{0, 1}, {-1, 0}}
        };
        double[][] out = new double[4][4];
        // M = sum_ab c_ab sigma_a (x) sigma_b, c_ab = a_a * b_b
        double[][] c = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) c[i][j] = a[i] * bConj[j];
        for (int mu = 0; mu < 4; mu++) {
            for (int nu = 0; nu < 4; nu++) {
                int r1 = mu / 2, c1 = nu / 2, r2 = mu % 2, c2 = nu % 2;
                double v = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) v += c[i][j] * sig[i][r1][c1] * sig[j][r2][c2];
                }
                out[mu][nu] = v;
            }
        }
        return out;
    }

    private static double[][] diattenuatorMatrix(double[] d, double D) {
        double[][] md = identity4();
        double[] dd = D < 1e-12 ? new double[3] : scaleV(d, 1.0 / Math.max(D, 1e-12));
        double s = D < 1.0 ? Math.sqrt(Math.max(0, 1 - D * D)) : 1e-6;
        double k = (s - 1.0);
        md[0][1] = d[0]; md[0][2] = d[1]; md[0][3] = d[2];
        md[1][0] = d[0]; md[2][0] = d[1]; md[3][0] = d[2];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) md[i + 1][j + 1] += k * dd[i] * dd[j];
        return md;
    }

    private static double[][] axisRotation(double[] n, double r) {
        double[][] x = {{0, -n[2], n[1]}, {n[2], 0, -n[0]}, {-n[1], n[0], 0}};
        double[][] out = identity3();
        double sin = Math.sin(r), c = Math.cos(r);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] += sin * x[i][j] + (1 - c) * (x[i][0] * x[0][j] + x[i][1] * x[1][j] + x[i][2] * x[2][j]);
            }
        }
        return out;
    }

    private static double[][] identity3() {
        return new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] identity4() {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) m[i][i] = 1;
        return m;
    }

    private static double[][] transpose(double[][] m) {
        double[][] t = new double[m.length][m[0].length];
        for (int i = 0; i < m.length; i++) for (int j = 0; j < m[0].length; j++) t[j][i] = m[i][j];
        return t;
    }

    private static double[][] mul4(double[][] a, double[][] b) {
        double[][] c = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            double v = 0;
            for (int k = 0; k < 4; k++) v += a[i][k] * b[k][j];
            c[i][j] = v;
        }
        return c;
    }

    private static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
             - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
             + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double[] scaleV(double[] v, double s) {
        double[] o = v.clone();
        for (int i = 0; i < o.length; i++) o[i] *= s;
        return o;
    }

    private static double[][] scale(double[][] m, double s) {
        double[][] o = new double[m.length][m[0].length];
        for (int i = 0; i < m.length; i++) for (int j = 0; j < m[0].length; j++) o[i][j] = m[i][j] * s;
        return o;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.5g", v);
    }
}
