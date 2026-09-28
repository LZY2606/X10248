package com.polarlab.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained linear algebra used by the Mueller pipeline.
 *
 * Design rule (see docs): near-singular design matrices are never force-inverted.
 * We compute the symmetric eigendecomposition of W^T W with a one-sided Jacobi
 * method (high relative accuracy on small singular values), expose singular
 * values / condition number / nullspace, and let callers decide.
 */
public final class LinearAlgebra {

    public static final class SVD {
        public final double[] singularValues; // length n, descending
        public final double[][] V;            // n x n orthonormal eigenvectors of A^T A
        public final double[][] U;            // m x k, k = number of positive singular values
        public final int rank;                // numerical rank w.r.t. tolerance
        public final double conditionNumber;  // smax / smin (over non-zero space); inf if rank deficient

        SVD(double[] s, double[][] v, double[][] u, int rank, double cond) {
            this.singularValues = s;
            this.V = v;
            this.U = u;
            this.rank = rank;
            this.conditionNumber = cond;
        }

        /** Right nullspace basis vectors (columns of V with negligible singular value). */
        public List<double[]> nullspaceBasis() {
            List<double[]> basis = new ArrayList<>();
            int n = singularValues.length;
            for (int j = rank; j < n; j++) {
                double[] col = new double[n];
                for (int i = 0; i < n; i++) {
                    col[i] = V[i][j];
                }
                basis.add(col);
            }
            return basis;
        }
    }

    private LinearAlgebra() {
    }

    public static double[][] transpose(double[][] a) {
        int m = a.length;
        int n = a[0].length;
        double[][] t = new double[n][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        int m = a.length;
        int k = a[0].length;
        int n = b[0].length;
        if (b.length != k) {
            throw new IllegalArgumentException("matrix dimensions do not match");
        }
        double[][] c = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int p = 0; p < k; p++) {
                    sum += a[i][p] * b[p][j];
                }
                c[i][j] = sum;
            }
        }
        return c;
    }

    public static double[] multiply(double[][] a, double[] x) {
        int m = a.length;
        int n = a[0].length;
        double[] y = new double[m];
        for (int i = 0; i < m; i++) {
            double sum = 0.0;
            for (int j = 0; j < n; j++) {
                sum += a[i][j] * x[j];
            }
            y[i] = sum;
        }
        return y;
    }

    /** Frobenius norm. */
    public static double frobenius(double[][] a) {
        double s = 0.0;
        for (double[] row : a) {
            for (double v : row) {
                s += v * v;
            }
        }
        return Math.sqrt(s);
    }

    public static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public static double[] scale(double[] a, double c) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * c;
        }
        return r;
    }

    public static double[][] scale(double[][] a, double c) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[0].length; j++) {
                r[i][j] = a[i][j] * c;
            }
        }
        return r;
    }

    public static double[][] add(double[][] a, double[][] b) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[0].length; j++) {
                r[i][j] = a[i][j] + b[i][j];
            }
        }
        return r;
    }

    /** Symmetric eigen decomposition via cyclic Jacobi rotations; eigenvalues ascending. */
    public static Jacobi jacobiSymmetric(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(aIn[i], 0, a[i], 0, n);
        }
        double[][] v = identity(n);
        int maxSweeps = 120;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            double off = 0.0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-300) {
                        continue;
                    }
                    double app = a[p][p];
                    double aqq = a[q][q];
                    double tau = (aqq - app) / (2.0 * apq);
                    double t;
                    if (tau >= 0) {
                        t = 1.0 / (tau + Math.sqrt(1.0 + tau * tau));
                    } else {
                        t = -1.0 / (-tau + Math.sqrt(1.0 + tau * tau));
                    }
                    double c = 1.0 / Math.sqrt(1.0 + t * t);
                    double s = t * c;
                    // rotate rows/cols p,q
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] eig = new double[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            eig[i] = a[i][i];
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(eig[x], eig[y]));
        double[] sortedEig = new double[n];
        double[][] sortedV = new double[n][n];
        for (int j = 0; j < n; j++) {
            sortedEig[j] = eig[idx[j]];
            for (int i = 0; i < n; i++) {
                sortedV[i][j] = v[i][idx[j]];
            }
        }
        return new Jacobi(sortedEig, sortedV);
    }

    public static final class Jacobi {
        public final double[] eigenvalues; // ascending
        public final double[][] V;

        Jacobi(double[] e, double[][] v) {
            this.eigenvalues = e;
            this.V = v;
        }
    }

    public static double[][] identity(int n) {
        double[][] i = new double[n][n];
        for (int k = 0; k < n; k++) {
            i[k][k] = 1.0;
        }
        return i;
    }

    /**
     * SVD of a tall/full matrix A (m x n, m >= n). Built from Jacobi EVD of
     * A^T A so small singular values retain high relative accuracy.
     */
    public static SVD svd(double[][] a, double rankTol) {
        int m = a.length;
        int n = a[0].length;
        double[][] ata = m >= n ? multiply(transpose(a), a) : multiply(a, transpose(a));
        int smallDim = Math.min(m, n);
        Jacobi ev = jacobiSymmetric(ata);
        double[] s2 = ev.eigenvalues; // ascending
        double[] s = new double[smallDim];
        double[][] vAsc = new double[smallDim][smallDim];
        for (int j = 0; j < smallDim; j++) {
            int src = smallDim - 1 - j; // descending singular values
            double val = s2[src];
            s[j] = val > 0 ? Math.sqrt(val) : 0.0;
            for (int i = 0; i < smallDim; i++) {
                vAsc[i][j] = ev.V[i][src];
            }
        }
        double smax = s[0];
        double tol = rankTol * Math.max(1.0, smax);
        int rank = 0;
        double sminPos = smax;
        for (double value : s) {
            if (value > tol) {
                rank++;
                sminPos = Math.min(sminPos, value);
            }
        }
        double cond = (rank == smallDim) ? (smax / sminPos) : Double.POSITIVE_INFINITY;

        // U columns for positive singular values: u = A v / s
        double[][] u = new double[m >= n ? m : n][rank];
        double[][] basisForU = (m >= n) ? a : transpose(a);
        int col = 0;
        for (int j = 0; j < smallDim; j++) {
            if (s[j] <= tol) {
                continue;
            }
            double[] vj = new double[smallDim];
            for (int i = 0; i < smallDim; i++) {
                vj[i] = vAsc[i][j];
            }
            double[] av = multiply(basisForU, vj);
            for (int i = 0; i < av.length; i++) {
                u[i][col] = av[i] / s[j];
            }
            col++;
        }
        // Reorder V into ascending-nullspace friendly layout: V columns currently descending.
        double[][] vDesc = vAsc;
        return new SVD(s, vDesc, u, rank, cond);
    }

    /**
     * Minimum-norm least squares solution of W x = y without ever forming an
     * explicit inverse: x = V_p (S_p^-1) U_p^T y. Caller is responsible for
     * checking rank/condition before treating x as unique.
     */
    public static double[] minNormLeastSquares(SVD svd, double[] y) {
        int n = svd.V.length;
        double[] x = new double[n];
        double tol = 1e-12 * Math.max(1.0, svd.singularValues[0]);
        for (int k = 0; k < svd.rank; k++) {
            double sigma = svd.singularValues[k];
            if (sigma <= tol) {
                continue;
            }
            double uty = 0.0;
            for (int i = 0; i < y.length; i++) {
                uty += svd.U[i][k] * y[i];
            }
            double coeff = uty / sigma;
            for (int i = 0; i < n; i++) {
                x[i] += svd.V[i][k] * coeff;
            }
        }
        return x;
    }

    public static String format(double v, int digits) {
        return String.format("%." + digits + "f", v);
    }
}
