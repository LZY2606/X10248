package pmr.math;

import java.util.Arrays;

/** Dense row-major real matrix with the small linear-algebra routines PMR needs (n &lt;= 16). */
public final class Matrix {
    public final int rows;
    public final int cols;
    private final double[] a;

    public Matrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows * cols];
    }

    public static Matrix zeros(int rows, int cols) {
        return new Matrix(rows, cols);
    }

    public static Matrix identity(int n) {
        Matrix m = new Matrix(n, n);
        for (int i = 0; i < n; i++) {
            m.set(i, i, 1.0);
        }
        return m;
    }

    public static Matrix fromRows(double[]... rowData) {
        Matrix m = new Matrix(rowData.length, rowData[0].length);
        for (int i = 0; i < rowData.length; i++) {
            for (int j = 0; j < rowData[i].length; j++) {
                m.set(i, j, rowData[i][j]);
            }
        }
        return m;
    }

    public double get(int row, int col) {
        return a[row * cols + col];
    }

    public void set(int row, int col, double value) {
        a[row * cols + col] = value;
    }

    public Matrix copy() {
        Matrix m = new Matrix(rows, cols);
        System.arraycopy(a, 0, m.a, 0, a.length);
        return m;
    }

    public Matrix transpose() {
        Matrix m = new Matrix(cols, rows);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                m.set(j, i, get(i, j));
            }
        }
        return m;
    }

    public Matrix multiply(Matrix other) {
        if (cols != other.rows) {
            throw new IllegalArgumentException("Shape mismatch " + shape() + " x " + other.shape());
        }
        Matrix result = new Matrix(rows, other.cols);
        for (int i = 0; i < rows; i++) {
            for (int k = 0; k < cols; k++) {
                double aik = get(i, k);
                if (aik == 0.0) {
                    continue;
                }
                for (int j = 0; j < other.cols; j++) {
                    result.a[i * result.cols + j] += aik * other.get(k, j);
                }
            }
        }
        return result;
    }

    public Matrix multiply(double scalar) {
        Matrix m = copy();
        for (int i = 0; i < m.a.length; i++) {
            m.a[i] *= scalar;
        }
        return m;
    }

    public Matrix plus(Matrix other) {
        if (rows != other.rows || cols != other.cols) {
            throw new IllegalArgumentException("Shape mismatch");
        }
        Matrix m = copy();
        for (int i = 0; i < m.a.length; i++) {
            m.a[i] += other.a[i];
        }
        return m;
    }

    public Matrix minus(Matrix other) {
        if (rows != other.rows || cols != other.cols) {
            throw new IllegalArgumentException("Shape mismatch");
        }
        Matrix m = copy();
        for (int i = 0; i < m.a.length; i++) {
            m.a[i] -= other.a[i];
        }
        return m;
    }

    public double[] multiply(double[] vector) {
        if (vector.length != cols) {
            throw new IllegalArgumentException("Vector length mismatch");
        }
        double[] out = new double[rows];
        for (int i = 0; i < rows; i++) {
            double sum = 0;
            for (int j = 0; j < cols; j++) {
                sum += get(i, j) * vector[j];
            }
            out[i] = sum;
        }
        return out;
    }

    public static Matrix column(double[] vector) {
        Matrix m = new Matrix(vector.length, 1);
        for (int i = 0; i < vector.length; i++) {
            m.set(i, 0, vector[i]);
        }
        return m;
    }

    public double[] columnData(int col) {
        double[] out = new double[rows];
        for (int i = 0; i < rows; i++) {
            out[i] = get(i, col);
        }
        return out;
    }

    public double frobenius() {
        double sum = 0;
        for (double v : a) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    public String shape() {
        return rows + "x" + cols;
    }

    @Override
    public String toString() {
        return Arrays.toString(a);
    }

    /**
     * One-sided Jacobi SVD for a tall matrix A: A = U diag(s) V^T.
     * Columns of U/V are zero-filled beyond the rank; singular values beyond rank are 0.
     */
    public Svd svd() {
        int n = cols;
        Matrix u = this.copy();
        Matrix v = identity(n);
        double[] singular = new double[n];

        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    double[] cp = u.columnData(p);
                    double[] cq = u.columnData(q);
                    double alpha = 0;
                    double beta = 0;
                    double gamma = 0;
                    for (int i = 0; i < rows; i++) {
                        alpha += cp[i] * cp[i];
                        beta += cq[i] * cq[i];
                        gamma += cp[i] * cq[i];
                    }
                    off += gamma * gamma;
                    if (Math.abs(gamma) <= 1e-14 * Math.sqrt(alpha * beta) + 1e-300) {
                        continue;
                    }
                    double zeta = (beta - alpha) / (2.0 * gamma);
                    double t = Math.copySign(1.0, zeta) / (Math.abs(zeta) + Math.sqrt(1.0 + zeta * zeta));
                    double cs = 1.0 / Math.sqrt(1.0 + t * t);
                    double sn = cs * t;
                    for (int i = 0; i < rows; i++) {
                        double x = u.get(i, p);
                        double y = u.get(i, q);
                        u.set(i, p, cs * x - sn * y);
                        u.set(i, q, sn * x + cs * y);
                    }
                    for (int i = 0; i < n; i++) {
                        double x = v.get(i, p);
                        double y = v.get(i, q);
                        v.set(i, p, cs * x - sn * y);
                        v.set(i, q, sn * x + cs * y);
                    }
                }
            }
            if (off < 1e-28) {
                break;
            }
        }

        for (int j = 0; j < n; j++) {
            double norm = 0;
            for (int i = 0; i < rows; i++) {
                norm += u.get(i, j) * u.get(i, j);
            }
            singular[j] = Math.sqrt(norm);
            if (singular[j] > 0) {
                double inv = 1.0 / singular[j];
                for (int i = 0; i < rows; i++) {
                    u.set(i, j, u.get(i, j) * inv);
                }
            }
        }

        // Sort descending.
        Integer[] order = new Integer[n];
        for (int j = 0; j < n; j++) {
            order[j] = j;
        }
        Arrays.sort(order, (x, y) -> Double.compare(singular[y], singular[x]));
        Matrix uSorted = new Matrix(rows, n);
        Matrix vSorted = new Matrix(n, n);
        double[] sSorted = new double[n];
        for (int j = 0; j < n; j++) {
            int src = order[j];
            sSorted[j] = singular[src];
            for (int i = 0; i < rows; i++) {
                uSorted.set(i, j, u.get(i, src));
            }
            for (int i = 0; i < n; i++) {
                vSorted.set(i, j, v.get(i, src));
            }
        }
        return new Svd(uSorted, sSorted, vSorted);
    }

    public static final class Svd {
        public final Matrix u;
        public final double[] s;
        public final Matrix v;

        public Svd(Matrix u, double[] s, Matrix v) {
            this.u = u;
            this.s = s;
            this.v = v;
        }

        public double conditionNumber() {
            double smax = s[0];
            double smin = 0;
            for (double value : s) {
                if (value > 0 && (smin == 0 || value < smin)) {
                    smin = value;
                }
            }
            if (smin == 0) {
                return Double.POSITIVE_INFINITY;
            }
            return smax / smin;
        }

        public int numericalRank(double relativeTolerance) {
            double threshold = s[0] * relativeTolerance * Math.max(u.rows, v.cols);
            int rank = 0;
            for (double value : s) {
                if (value > threshold) {
                    rank++;
                }
            }
            return rank;
        }

        /**
         * Truncated pseudoinverse solution. Never used silently: callers must record
         * rank/nullity and the truncation tolerance in the derived node fingerprint.
         */
        public double[] solveTruncated(double[] rhs, double relativeTolerance) {
            int n = s.length;
            double threshold = s[0] * relativeTolerance * Math.max(u.rows, v.cols);
            double[] utb = new double[n];
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int i = 0; i < rhs.length; i++) {
                    sum += u.get(i, j) * rhs[i];
                }
                utb[j] = sum;
            }
            double[] y = new double[n];
            for (int j = 0; j < n; j++) {
                y[j] = s[j] > threshold ? utb[j] / s[j] : 0.0;
            }
            double[] x = new double[n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    x[i] += v.get(i, j) * y[j];
                }
            }
            return x;
        }

        /** Null-space basis columns V[:,j] for singular values at or below the threshold. */
        public Matrix nullspace(double relativeTolerance) {
            int n = s.length;
            double threshold = s[0] * relativeTolerance * Math.max(u.rows, v.cols);
            int count = 0;
            for (double value : s) {
                if (value <= threshold) {
                    count++;
                }
            }
            Matrix basis = new Matrix(n, Math.max(count, 0));
            int col = 0;
            for (int j = 0; j < n; j++) {
                if (s[j] <= threshold) {
                    for (int i = 0; i < n; i++) {
                        basis.set(i, col, v.get(i, j));
                    }
                    col++;
                }
            }
            return basis;
        }
    }
}
