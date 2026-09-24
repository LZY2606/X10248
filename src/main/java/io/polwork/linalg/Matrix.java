package io.polwork.linalg;

import java.util.Arrays;

/** Dense row-major real matrix with the small linear algebra routines the chamber needs. */
public final class Matrix {
    public final int rows;
    public final int cols;
    private final double[][] a;

    public Matrix(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("non-positive dimension");
        }
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows][cols];
    }

    public static Matrix of(double[][] data) {
        Matrix m = new Matrix(data.length, data[0].length);
        for (int i = 0; i < m.rows; i++) {
            if (data[i].length != m.cols) {
                throw new IllegalArgumentException("ragged data");
            }
            System.arraycopy(data[i], 0, m.a[i], 0, m.cols);
        }
        return m;
    }

    public static Matrix identity(int n) {
        Matrix m = new Matrix(n, n);
        for (int i = 0; i < n; i++) {
            m.a[i][i] = 1.0;
        }
        return m;
    }

    public static Matrix diag(double[] d) {
        Matrix m = new Matrix(d.length, d.length);
        for (int i = 0; i < d.length; i++) {
            m.a[i][i] = d[i];
        }
        return m;
    }

    public double get(int i, int j) {
        return a[i][j];
    }

    public void set(int i, int j, double v) {
        a[i][j] = v;
    }

    public double[][] toArray() {
        double[][] out = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            out[i] = Arrays.copyOf(a[i], cols);
        }
        return out;
    }

    public Matrix copy() {
        return Matrix.of(toArray());
    }

    public Matrix transpose() {
        Matrix t = new Matrix(cols, rows);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                t.a[j][i] = a[i][j];
            }
        }
        return t;
    }

    public Matrix mul(Matrix o) {
        if (cols != o.rows) {
            throw new IllegalArgumentException("shape mismatch");
        }
        Matrix r = new Matrix(rows, o.cols);
        for (int i = 0; i < rows; i++) {
            for (int k = 0; k < cols; k++) {
                double aik = a[i][k];
                if (aik == 0.0) {
                    continue;
                }
                for (int j = 0; j < o.cols; j++) {
                    r.a[i][j] += aik * o.a[k][j];
                }
            }
        }
        return r;
    }

    public Matrix mul(double s) {
        Matrix r = new Matrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                r.a[i][j] = a[i][j] * s;
            }
        }
        return r;
    }

    public Matrix add(Matrix o) {
        if (rows != o.rows || cols != o.cols) {
            throw new IllegalArgumentException("shape mismatch");
        }
        Matrix r = new Matrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                r.a[i][j] = a[i][j] + o.a[i][j];
            }
        }
        return r;
    }

    public Matrix sub(Matrix o) {
        return add(o.mul(-1.0));
    }

    public double[] mulVec(double[] x) {
        if (cols != x.length) {
            throw new IllegalArgumentException("shape mismatch");
        }
        double[] y = new double[rows];
        for (int i = 0; i < rows; i++) {
            double s = 0.0;
            for (int j = 0; j < cols; j++) {
                s += a[i][j] * x[j];
            }
            y[i] = s;
        }
        return y;
    }

    /** Direct Kronecker product A (x) B. */
    public static Matrix kron(Matrix a, Matrix b) {
        Matrix r = new Matrix(a.rows * b.rows, a.cols * b.cols);
        for (int i = 0; i < a.rows; i++) {
            for (int j = 0; j < a.cols; j++) {
                double v = a.a[i][j];
                for (int p = 0; p < b.rows; p++) {
                    for (int q = 0; q < b.cols; q++) {
                        r.a[i * b.rows + p][j * b.cols + q] = v * b.a[p][q];
                    }
                }
            }
        }
        return r;
    }

    public double frobeniusNorm() {
        double s = 0.0;
        for (double[] row : a) {
            for (double v : row) {
                s += v * v;
            }
        }
        return Math.sqrt(s);
    }

    /** Inverse of a square matrix via Gauss-Jordan; throws SingularMatrixException. */
    public Matrix inverse() {
        if (rows != cols) {
            throw new IllegalArgumentException("not square");
        }
        int n = rows;
        double[][] m = toArray();
        double[][] inv = identity(n).toArray();
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double best = Math.abs(m[col][col]);
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > best) {
                    best = Math.abs(m[r][col]);
                    pivot = r;
                }
            }
            if (best < 1e-14) {
                throw new SingularMatrixException("zero pivot at column " + col);
            }
            if (pivot != col) {
                double[] t = m[col]; m[col] = m[pivot]; m[pivot] = t;
                t = inv[col]; inv[col] = inv[pivot]; inv[pivot] = t;
            }
            double d = m[col][col];
            for (int j = 0; j < n; j++) {
                m[col][j] /= d;
                inv[col][j] /= d;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double f = m[r][col];
                if (f == 0.0) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    m[r][j] -= f * m[col][j];
                    inv[r][j] -= f * inv[col][j];
                }
            }
        }
        return Matrix.of(inv);
    }

    /** Symmetric positive definite matrix square root via Jacobi eigendecomposition. */
    public Matrix spdSqrt() {
        if (rows != cols) {
            throw new IllegalArgumentException("not square");
        }
        JacobiResult e = jacobiSymmetric(copy());
        double[][] v = e.vectors;
        double[] lambda = e.values;
        int n = rows;
        double[][] sqrt = new double[n][n];
        for (int i = 0; i < n; i++) {
            double li = Math.max(lambda[i], 0.0);
            double root = Math.sqrt(li);
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    sqrt[j][k] += v[j][i] * root * v[k][i];
                }
            }
        }
        return Matrix.of(sqrt);
    }

    /** Eigenvalues and eigenvectors of a real symmetric matrix, columns of vectors are eigenvectors. */
    public static JacobiResult jacobiSymmetric(Matrix sym) {
        int n = sym.rows;
        double[][] m = sym.toArray();
        double[][] v = identity(n).toArray();
        int maxSweeps = 100;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            double off = 0.0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += m[p][q] * m[p][q];
                }
            }
            if (off < 1e-28) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = m[p][q];
                    if (Math.abs(apq) < 1e-16) {
                        continue;
                    }
                    double app = m[p][p];
                    double aqq = m[q][q];
                    double phi;
                    if (Math.abs(app - aqq) < 1e-300) {
                        phi = (apq >= 0 ? Math.PI / 4 : -Math.PI / 4);
                    } else {
                        phi = 0.5 * Math.atan2(2 * apq, app - aqq);
                    }
                    double c = Math.cos(phi);
                    double s = Math.sin(phi);
                    double[][] newM = new double[n][n];
                    for (int i = 0; i < n; i++) {
                        newM[i] = Arrays.copyOf(m[i], n);
                    }
                    newM[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq;
                    newM[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq;
                    newM[p][q] = 0.0;
                    newM[q][p] = 0.0;
                    for (int r = 0; r < n; r++) {
                        if (r != p && r != q) {
                            double arp = m[r][p];
                            double arq = m[r][q];
                            newM[r][p] = c * arp - s * arq;
                            newM[p][r] = newM[r][p];
                            newM[r][q] = s * arp + c * arq;
                            newM[q][r] = newM[r][q];
                        }
                    }
                    double[][] newV = new double[n][n];
                    for (int i = 0; i < n; i++) {
                        for (int j = 0; j < n; j++) {
                            newV[i][j] = v[i][j];
                        }
                    }
                    for (int r = 0; r < n; r++) {
                        double vrp = v[r][p];
                        double vrq = v[r][q];
                        newV[r][p] = c * vrp - s * vrq;
                        newV[r][q] = s * vrp + c * vrq;
                    }
                    m = newM;
                    v = newV;
                }
            }
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = m[i][i];
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Double.compare(values[y], values[x]));
        double[] sortedValues = new double[n];
        double[][] sortedVectors = new double[n][n];
        for (int i = 0; i < n; i++) {
            sortedValues[i] = values[order[i]];
            for (int r = 0; r < n; r++) {
                sortedVectors[r][i] = v[r][order[i]];
            }
        }
        return new JacobiResult(sortedValues, sortedVectors);
    }

    /**
     * Economy SVD of a tall matrix A via A^T A eigendecomposition. Good enough for
     * well-conditioned and mildly ill-conditioned design matrices used in the chamber.
     * Returns singular values sorted descending and right singular vectors as columns.
     */
    public static SvdResult thinSvd(Matrix a) {
        Matrix ata = a.transpose().mul(a);
        JacobiResult e = jacobiSymmetric(ata);
        int k = e.values.length;
        double[] sigma = new double[k];
        for (int i = 0; i < k; i++) {
            sigma[i] = Math.sqrt(Math.max(e.values[i], 0.0));
        }
        return new SvdResult(sigma, Matrix.of(e.vectors));
    }

    public static final class JacobiResult {
        public final double[] values;
        public final double[][] vectors;

        public JacobiResult(double[] values, double[][] vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    public static final class SvdResult {
        public final double[] singularValues;
        public final Matrix rightVectors;

        public SvdResult(double[] singularValues, Matrix rightVectors) {
            this.singularValues = singularValues;
            this.rightVectors = rightVectors;
        }

        public double conditionNumber() {
            double max = singularValues[0];
            double min = singularValues[singularValues.length - 1];
            if (min <= max * 1e-15) {
                return Double.POSITIVE_INFINITY;
            }
            return max / min;
        }

        public int numericalRank(double relTol) {
            double tol = singularValues[0] * relTol;
            int rank = 0;
            for (double s : singularValues) {
                if (s > tol) {
                    rank++;
                }
            }
            return rank;
        }
    }

    public static final class SingularMatrixException extends RuntimeException {
        public SingularMatrixException(String message) {
            super(message);
        }
    }
}
