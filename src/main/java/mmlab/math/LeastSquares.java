package mmlab.math;

/**
 * Weighted least squares solved through an eigen decomposition of the normal
 * matrix. Singular/near-singular designs yield a minimum-norm solution plus an
 * explicit null space rather than a numerically inverted matrix.
 */
public final class LeastSquares {

    private LeastSquares() {
    }

    public static WlsResult solve(Mat design, double[] y, double[] weights) {
        int n = design.r;
        int p = design.c;
        Mat aw = new Mat(n, p);
        double[] yw = new double[n];
        for (int i = 0; i < n; i++) {
            double wi = weights[i];
            yw[i] = wi * y[i];
            for (int j = 0; j < p; j++) {
                aw.set(i, j, wi * design.get(i, j));
            }
        }
        Mat normal = aw.transpose().mul(aw);
        Mat.Eigh e = Mat.eighSym(normal);
        double[] lambda = e.values;
        double lambdaMax = Math.max(lambda[lambda.length - 1], 0.0);
        double tol = Math.max(n, p) * Math.max(lambdaMax, 1e-300) * 1e-11;

        double[] sigma = new double[p];
        for (int j = 0; j < p; j++) {
            sigma[j] = Math.sqrt(Math.max(lambda[j], 0.0));
        }
        int rank = 0;
        double sigmaMinPositive = Double.POSITIVE_INFINITY;
        for (int j = 0; j < p; j++) {
            if (lambda[j] > tol) {
                rank++;
                sigmaMinPositive = Math.min(sigmaMinPositive, sigma[j]);
            }
        }
        double sigmaMax = sigma[sigma.length - 1];
        double conditionNumber;
        if (rank < p) {
            conditionNumber = Double.POSITIVE_INFINITY;
        } else {
            conditionNumber = sigmaMax / sigmaMinPositive;
        }

        double[] aty = aw.transpose().mulVec(yw);
        double[] x = new double[p];
        Mat cov = new Mat(p, p);
        for (int k = 0; k < p; k++) {
            if (lambda[k] > tol) {
                double proj = 0;
                for (int j = 0; j < p; j++) {
                    proj += e.vectors.get(j, k) * aty[j];
                }
                for (int j = 0; j < p; j++) {
                    x[j] += e.vectors.get(j, k) * proj / lambda[k];
                }
                for (int i = 0; i < p; i++) {
                    for (int j = 0; j < p; j++) {
                        cov.a[i * p + j] += e.vectors.get(i, k) * e.vectors.get(j, k) / lambda[k];
                    }
                }
            }
        }

        int nullity = p - rank;
        double[] nullBasis = new double[nullity * p];
        int nb = 0;
        for (int k = 0; k < p; k++) {
            if (lambda[k] <= tol) {
                for (int j = 0; j < p; j++) {
                    nullBasis[nb * p + j] = e.vectors.get(j, k);
                }
                nb++;
            }
        }

        double[] residual = new double[n];
        double chi = 0;
        for (int i = 0; i < n; i++) {
            double pred = 0;
            for (int j = 0; j < p; j++) {
                pred += design.get(i, j) * x[j];
            }
            residual[i] = (y[i] - pred) * weights[i];
            chi += residual[i] * residual[i];
        }
        double rnorm = 0;
        for (double v : residual) {
            rnorm += v * v;
        }
        int dof = Math.max(n - rank, 0);
        return new WlsResult(x, sigma, e.vectors, rank, conditionNumber, tol,
                rank == p ? cov : null, Math.sqrt(rnorm), nullBasis, chi, dof);
    }

    public static double[] solveIdentityWeights(Mat design, double[] y) {
        double[] w = new double[design.r];
        java.util.Arrays.fill(w, 1.0);
        return solve(design, y, w).solution;
    }
}
