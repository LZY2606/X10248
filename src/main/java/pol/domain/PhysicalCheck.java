package pol.domain;

import java.util.Arrays;

import org.ejml.data.DMatrixRMaj;

/**
 * Physical-realizability checks for a Mueller matrix M.
 *
 * A Mueller matrix is physically realizable iff its normalized Cloude
 * coherency superoperator
 *   H(Mhat) = 1/4 sum_{mu,nu} Mhat_{mu,nu} sigma_mu \otimes sigma_nu^*
 * (sigma_0 = I, (sigma_1,sigma_2,sigma_3) the Pauli matrices with the
 * imaginary sigma_y) is positive semidefinite. Equivalent scalar reports:
 * m00 &gt; 0, DI(M) &lt;= 1, and min eigenvalue lambda_min &gt;= 0 (sum = 1).
 *
 * Non-physical candidates are flagged with their violations; this layer never
 * projects a candidate onto the physical cone and presents the projection as
 * the original solution.
 */
public final class PhysicalCheck {
    private PhysicalCheck() {
    }

    public record Violation(String code, double value, double bound, String message) {
    }

    public record Result(
            boolean physicallyRealizable,
            double m00,
            double degreeOfPolarimetricPurity,
            double[] coherencyEigenvalues,
            double minEigenvalue,
            double maxEigenvalue,
            double eigenvalueSum,
            java.util.List<Violation> violations) {

        public double worstResidual() {
            double worst = 0.0;
            for (Violation v : violations) {
                worst = Math.max(worst, Math.abs(v.value() - v.bound()));
            }
            return worst;
        }
    }

    public static double degreeOfPolarimetricPurity(DMatrixRMaj m) {
        double sumSquares = 0.0;
        for (int i = 0; i < 16; i++) {
            double v = m.get(i);
            sumSquares += v * v;
        }
        double m00 = Math.abs(m.get(0));
        if (m00 == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double di2 = (sumSquares - m00 * m00) / (3.0 * m00 * m00);
        return Math.sqrt(Math.max(0.0, di2));
    }

    /**
     * Cloude coherency superoperator as a complex 4x4 matrix.
     * Index of a tensor-product basis state (a,b) is 2a+b.
     */
    public static Cplx[][] coherencyComplex(DMatrixRMaj m) {
        double m00 = m.get(0, 0);
        double scale = 0.25 / (m00 != 0.0 ? m00 : 1.0);
        Cplx[][][] sigma = Pauli.complexPauli();
        Cplx[][] h = new Cplx[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                h[i][j] = Cplx.ZERO;
            }
        }
        for (int mu = 0; mu < 4; mu++) {
            for (int nu = 0; nu < 4; nu++) {
                Cplx[][] k = kronConj(sigma[mu], sigma[nu]);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        h[i][j] = Cplx.add(h[i][j], new Cplx(scale * m.get(mu, nu) * k[i][j].re(),
                                scale * m.get(mu, nu) * k[i][j].im()));
                    }
                }
            }
        }
        return h;
    }

    static Cplx[][] kronConj(Cplx[][] a, Cplx[][] b) {
        Cplx[][] out = new Cplx[4][4];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int u = 0; u < 2; u++) {
                    for (int v = 0; v < 2; v++) {
                        out[2 * i + u][2 * j + v] = Cplx.mul(a[i][j], b[u][v].conj());
                    }
                }
            }
        }
        return out;
    }

    /** Eigenvalues descending; a unitary diagonal phase makes H real first. */
    public static double[] realEigenvaluesOfHermitian(Cplx[][] h) {
        DMatrixRMaj real = hermitianToRealSymmetric(h);
        double[] values = eigenRealSymmetric(real);
        return values;
    }

    static DMatrixRMaj hermitianToRealSymmetric(Cplx[][] h) {
        Cplx[] phase = {new Cplx(1, 0), new Cplx(1, 0), new Cplx(1, 0), new Cplx(0, -1)};
        DMatrixRMaj r = Mtx.zeros(4, 4);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Cplx v = Cplx.mul(phase[i].conj(), Cplx.mul(h[i][j], phase[j]));
                r.set(i, j, v.re());
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                double avg = 0.5 * (r.get(i, j) + r.get(j, i));
                r.set(i, j, avg);
                r.set(j, i, avg);
            }
        }
        return r;
    }

    static double[] eigenRealSymmetric(DMatrixRMaj sym) {
        double[][] vecs = JacobiEigen.realSymmetricEigenvectors(sym);
        double[] values = new double[4];
        for (int k = 0; k < 4; k++) {
            double num = 0.0;
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    num += vecs[i][k] * sym.get(i, j) * vecs[j][k];
                }
            }
            values[k] = num;
        }
        Arrays.sort(values);
        return reverse(values);
    }

    private static double[] reverse(double[] ascending) {
        double[] out = new double[ascending.length];
        for (int i = 0; i < ascending.length; i++) {
            out[i] = ascending[ascending.length - 1 - i];
        }
        return out;
    }

    /** Real symmetric 4x4 eigenvalues, descending (eigenvectors via Jacobi). */
    public static double[] realEigenvalues(DMatrixRMaj h) {
        return eigenRealSymmetric(h);
    }

    /** Sorted (descending) complex eigenvectors of the 4x4 coherency matrix. */
    public static Cplx[][] eigenvectors(Cplx[][] h) {
        DMatrixRMaj real = hermitianToRealSymmetric(h);
        double[][] realVecs = JacobiEigen.realSymmetricEigenvectors(real);
        Cplx[] phase = {new Cplx(1, 0), new Cplx(1, 0), new Cplx(1, 0), new Cplx(0, -1)};
        Cplx[][] out = new Cplx[4][4];
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < 4; i++) {
                out[i][k] = Cplx.mul(phase[i], new Cplx(realVecs[i][k], 0));
            }
        }
        return out;
    }

    public static Result check(DMatrixRMaj m) {
        double m00 = m.get(0, 0);
        double di = degreeOfPolarimetricPurity(m);
        double[] eig = m00 != 0.0
                ? realEigenvaluesOfHermitian(coherencyComplex(m))
                : new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double min = eig[3];
        double max = eig[0];
        double sum = eig[0] + eig[1] + eig[2] + eig[3];

        java.util.List<Violation> violations = new java.util.ArrayList<>();
        if (!(m00 > 0.0)) {
            violations.add(new Violation("m00_positive", m00, 0.0,
                    "mean-intensity element m00 must be strictly positive"));
        }
        if (di > 1.0 + 1e-9) {
            violations.add(new Violation("di_le_one", di, 1.0,
                    "degree of polarimetric purity DI must not exceed 1"));
        }
        if (min < -1e-9) {
            violations.add(new Violation("coherency_psd", min, 0.0,
                    "Cloude coherency superoperator must be positive semidefinite"));
        }
        if (Double.isFinite(sum) && Math.abs(sum - 1.0) > 1e-7) {
            violations.add(new Violation("eigenvalue_sum_one", sum, 1.0,
                    "normalized coherency eigenvalues should sum to 1"));
        }
        if (!Double.isFinite(di) || Double.isNaN(min)) {
            violations.add(new Violation("finite_matrix", Double.NaN, 0.0, "matrix contains non-finite entries"));
        }
        return new Result(violations.isEmpty(), m00, di, eig, min, max, sum, violations);
    }
}
