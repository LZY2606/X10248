package pol.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;
import org.ejml.interfaces.decomposition.EigenDecomposition;
import org.ejml.simple.SimpleMatrix;

import java.util.Arrays;

/**
 * Numerical primitives shared by the estimator. The defining rule for this
 * room: a near-singular design matrix is never force-inverted. SVD is used and
 * a threshold separates retained from truncated singular values.
 */
public final class Linalg {

    public static final double SINGULAR_REL_TOL = 1e-9;

    private Linalg() {
    }

    public static SimpleMatrix m(double[][] a) {
        return new SimpleMatrix(a);
    }

    public static double[][] toArray(SimpleMatrix matrix) {
        double[][] out = new double[matrix.numRows()][matrix.numCols()];
        for (int i = 0; i < matrix.numRows(); i++) {
            for (int j = 0; j < matrix.numCols(); j++) {
                out[i][j] = matrix.get(i, j);
            }
        }
        return out;
    }

    /** Row-major Kronecker so that X_k = a ⊗ g gives aᵀ M g. */
    public static double[] kronRow(double[] a, double[] g) {
        int n = a.length;
        int p = g.length;
        double[] out = new double[n * p];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < p; j++) {
                out[i * p + j] = a[i] * g[j];
            }
        }
        return out;
    }

    public static final class Svd {
        public final double[] singular;
        public final SimpleMatrix u;
        public final SimpleMatrix v;
        public final double condition;
        public final int rank;
        public final double threshold;

        Svd(double[] singular, SimpleMatrix u, SimpleMatrix v, int rank, double threshold) {
            this.singular = singular;
            this.u = u;
            this.v = v;
            this.rank = rank;
            this.threshold = threshold;
            this.condition = singular[singular.length - 1] > 0
                    ? singular[0] / singular[singular.length - 1] : Double.POSITIVE_INFINITY;
        }
    }

    public static Svd svd(SimpleMatrix x) {
        SingularValueDecomposition<DMatrixRMaj> d =
                DecompositionFactory_DDRM.svd(x.numRows(), x.numCols(), true, true, false);
        DMatrixRMaj xd = x.getMatrix();
        if (!d.decompose(xd.copy())) {
            throw new IllegalStateException("SVD failed");
        }
        double[] s = d.getSingularValues().clone();
        Arrays.sort(s);
        for (int i = 0; i < s.length / 2; i++) {
            double t = s[i];
            s[i] = s[s.length - 1 - i];
            s[s.length - 1 - i] = t;
        }
        double threshold = s[0] * SINGULAR_REL_TOL;
        int rank = 0;
        for (double v : s) {
            if (v > threshold) {
                rank++;
            }
        }
        SimpleMatrix u = SimpleMatrix.wrap(d.getU(null, true));
        SimpleMatrix v = SimpleMatrix.wrap(d.getV(null, true));
        return new Svd(s, u, v, rank, threshold);
    }

    public static final class Solve {
        public final double[] coefficients;
        public final double[][] covariance;
        public final Svd svd;
        public final boolean unique;
        public final double[][] nullspace;
        public final double residualNorm;

        Solve(double[] c, double[][] cov, Svd svd, boolean unique, double[][] ns, double rnorm) {
            this.coefficients = c;
            this.covariance = cov;
            this.svd = svd;
            this.unique = unique;
            this.nullspace = ns;
            this.residualNorm = rnorm;
        }
    }

    /**
     * Truncated-SVD minimum-norm solution for X m = y with measurement
     * covariance C. Near-singular directions are dropped, never inverted.
     * Covariance of the retained (minimum-norm) estimate is propagated through
     * the same pseudo-inverse, supporting correlated errors.
     */
    public static Solve svdSolve(double[][] xArr, double[] y, double[][] covArr) {
        SimpleMatrix x = new SimpleMatrix(xArr);
        int n = x.numRows();
        int p = x.numCols();
        Svd svd = svd(x);

        SimpleMatrix c = covArr == null ? SimpleMatrix.identity(n) : new SimpleMatrix(covArr);
        SimpleMatrix whiten;
        try {
            whiten = inverseSqrt(c);
        } catch (Exception e) {
            whiten = SimpleMatrix.identity(n);
        }
        SimpleMatrix xw = whiten.mult(x);
        SimpleMatrix yw = whiten.mult(new SimpleMatrix(n, 1, true, y.clone()));
        Svd svdw = svd(xw);

        int rank = svdw.rank;
        double[] s = svdw.singular;
        SimpleMatrix u = svdw.u;
        SimpleMatrix v = svdw.v;

        double[] coef = new double[p];
        for (int k = 0; k < rank; k++) {
            double d = 0;
            for (int i = 0; i < n; i++) {
                d += u.get(i, k) * yw.get(i);
            }
            d /= s[k];
            for (int j = 0; j < p; j++) {
                coef[j] += v.get(j, k) * d;
            }
        }

        // After whitening, yw has unit covariance, so the minimum-norm
        // covariance is A Aᵀ with A the pseudo-inverse of the whitened design.
        SimpleMatrix pinv = pinvFromSvd(u, v, s, rank, n);
        double[][] cov = toArray(pinv.mult(pinv.transpose()));

        int nullity = p - rank;
        double[][] ns = new double[nullity][p];
        for (int k = rank; k < p; k++) {
            for (int j = 0; j < p; j++) {
                ns[k - rank][j] = v.get(j, k);
            }
        }

        SimpleMatrix pred = x.mult(new SimpleMatrix(p, 1, true, coef.clone()));
        double rnorm = 0;
        for (int i = 0; i < n; i++) {
            double e = y[i] - pred.get(i);
            rnorm += e * e;
        }
        rnorm = Math.sqrt(rnorm);

        boolean unique = nullity == 0 && svd.condition < 1e9;
        return new Solve(coef, cov, svd, unique, ns, rnorm);
    }

    private static SimpleMatrix pinvFromSvd(SimpleMatrix u, SimpleMatrix v, double[] s, int rank, int n) {
        int p = v.numRows();
        SimpleMatrix pinv = new SimpleMatrix(p, n);
        for (int k = 0; k < rank; k++) {
            for (int i = 0; i < p; i++) {
                for (int j = 0; j < n; j++) {
                    pinv.set(i, j, pinv.get(i, j) + v.get(i, k) * u.get(j, k) / s[k]);
                }
            }
        }
        return pinv;
    }

    /** C^{-1/2} via symmetric eigendecomposition (whitening). */
    public static SimpleMatrix inverseSqrt(SimpleMatrix c) {
        SimpleEigen symmetric = symmetricEigen(c);
        int n = c.numRows();
        SimpleMatrix out = new SimpleMatrix(n, n);
        for (int k = 0; k < n; k++) {
            double lam = Math.max(symmetric.values[k], 1e-30);
            double f = 1.0 / Math.sqrt(lam);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    out.set(i, j, out.get(i, j) + f * symmetric.vectors[i][k] * symmetric.vectors[j][k]);
                }
            }
        }
        return out;
    }

    public static SimpleMatrix sqrtm(SimpleMatrix a) {
        SimpleEigen e = symmetricEigen(symmetrize(a));
        int n = a.numRows();
        SimpleMatrix out = new SimpleMatrix(n, n);
        for (int k = 0; k < n; k++) {
            double f = Math.sqrt(Math.max(e.values[k], 0));
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    out.set(i, j, out.get(i, j) + f * e.vectors[i][k] * e.vectors[j][k]);
                }
            }
        }
        return out;
    }

    public static SimpleMatrix inverseSqrtGeneral(SimpleMatrix a) {
        SimpleEigen e = symmetricEigen(symmetrize(a));
        int n = a.numRows();
        SimpleMatrix out = new SimpleMatrix(n, n);
        for (int k = 0; k < n; k++) {
            double f = 1.0 / Math.sqrt(Math.max(e.values[k], 1e-30));
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    out.set(i, j, out.get(i, j) + f * e.vectors[i][k] * e.vectors[j][k]);
                }
            }
        }
        return out;
    }

    public static SimpleMatrix symmetrize(SimpleMatrix a) {
        int n = a.numRows();
        SimpleMatrix out = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                out.set(i, j, 0.5 * (a.get(i, j) + a.get(j, i)));
            }
        }
        return out;
    }

    public static SimpleMatrix invert(SimpleMatrix a) {
        SimpleMatrix out = new SimpleMatrix(a.numRows(), a.numCols());
        CommonOps_DDRM.invert(a.getMatrix(), out.getMatrix());
        return out;
    }

    public static final class SimpleEigen {
        public final double[] values;
        public final double[][] vectors;

        SimpleEigen(double[] values, double[][] vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /** Eigen of a real symmetric matrix, ascending eigenvalues. */
    public static SimpleEigen symmetricEigen(SimpleMatrix a) {
        int n = a.numRows();
        DMatrixRMaj ad = symmetrize(a).getMatrix();
        EigenDecomposition<DMatrixRMaj> eig = DecompositionFactory_DDRM.eig(n, true);
        if (!eig.decompose(ad.copy())) {
            throw new IllegalStateException("eigendecomposition failed");
        }
        double[] vals = new double[n];
        double[][] vecs = new double[n][n];
        for (int k = 0; k < n; k++) {
            vals[k] = eig.getEigenvalue(k).getReal();
            DMatrixRMaj v = eig.getEigenVector(k);
            for (int i = 0; i < n; i++) {
                vecs[i][k] = v == null ? 0 : v.get(i);
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (vals[j] < vals[i]) {
                    double t = vals[i];
                    vals[i] = vals[j];
                    vals[j] = t;
                    for (int r = 0; r < n; r++) {
                        double tt = vecs[r][i];
                        vecs[r][i] = vecs[r][j];
                        vecs[r][j] = tt;
                    }
                }
            }
        }
        return new SimpleEigen(vals, vecs);
    }
}
