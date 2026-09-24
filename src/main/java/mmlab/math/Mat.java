package mmlab.math;

import java.util.Locale;

/**
 * Dense double matrix in row-major order. Small, dependency-free linear algebra
 * tailored to the Mueller laboratory (4x4 / up to 32x32 problems).
 */
public final class Mat {
    public final int r;
    public final int c;
    public final double[] a;

    public Mat(int r, int c) {
        this.r = r;
        this.c = c;
        this.a = new double[r * c];
    }

    public static Mat of(double[][] rows) {
        Mat m = new Mat(rows.length, rows[0].length);
        for (int i = 0; i < rows.length; i++) {
            System.arraycopy(rows[i], 0, m.a, i * m.c, rows[i].length);
        }
        return m;
    }

    public double get(int i, int j) {
        return a[i * c + j];
    }

    public Mat set(int i, int j, double v) {
        a[i * c + j] = v;
        return this;
    }

    public Mat copy() {
        Mat m = new Mat(r, c);
        System.arraycopy(a, 0, m.a, 0, a.length);
        return m;
    }

    public static Mat identity(int n) {
        Mat m = new Mat(n, n);
        for (int i = 0; i < n; i++) {
            m.set(i, i, 1.0);
        }
        return m;
    }

    public Mat transpose() {
        Mat t = new Mat(c, r);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                t.set(j, i, get(i, j));
            }
        }
        return t;
    }

    public Mat mul(double s) {
        Mat m = new Mat(r, c);
        for (int k = 0; k < a.length; k++) {
            m.a[k] = a[k] * s;
        }
        return m;
    }

    public Mat mul(Mat b) {
        if (c != b.r) {
            throw new IllegalArgumentException("shape mismatch " + r + "x" + c + " * " + b.r + "x" + b.c);
        }
        Mat m = new Mat(r, b.c);
        for (int i = 0; i < r; i++) {
            for (int k = 0; k < c; k++) {
                double aik = get(i, k);
                for (int j = 0; j < b.c; j++) {
                    m.a[i * m.c + j] += aik * b.a[k * b.c + j];
                }
            }
        }
        return m;
    }

    public Mat add(Mat b) {
        if (r != b.r || c != b.c) {
            throw new IllegalArgumentException("shape mismatch");
        }
        Mat m = new Mat(r, c);
        for (int k = 0; k < a.length; k++) {
            m.a[k] = a[k] + b.a[k];
        }
        return m;
    }

    public Mat sub(Mat b) {
        Mat m = new Mat(r, c);
        for (int k = 0; k < a.length; k++) {
            m.a[k] = a[k] - b.a[k];
        }
        return m;
    }

    public double[] mulVec(double[] x) {
        if (x.length != c) {
            throw new IllegalArgumentException("vector length mismatch");
        }
        double[] y = new double[r];
        for (int i = 0; i < r; i++) {
            double s = 0;
            for (int j = 0; j < c; j++) {
                s += get(i, j) * x[j];
            }
            y[i] = s;
        }
        return y;
    }

    public double frobenius() {
        double s = 0;
        for (double v : a) {
            s += v * v;
        }
        return Math.sqrt(s);
    }

    public double trace() {
        double s = 0;
        int n = Math.min(r, c);
        for (int i = 0; i < n; i++) {
            s += get(i, i);
        }
        return s;
    }

    /** Frobenius relative difference. */
    public static double relDiff(Mat a, Mat b) {
        Mat d = a.sub(b);
        double denom = Math.max(a.frobenius(), 1e-300);
        return d.frobenius() / denom;
    }

    /**
     * Eigen decomposition of a real symmetric matrix via cyclic Jacobi rotations.
     * Returns eigenvalues ascending and orthonormal eigenvectors as columns.
     */
    public static Eigh eighSym(Mat sIn) {
        if (sIn.r != sIn.c) {
            throw new IllegalArgumentException("eigh requires square matrix");
        }
        int n = sIn.r;
        Mat s = sIn.copy();
        Mat v = identity(n);
        double tolerance = 1e-14;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += s.get(p, q) * s.get(p, q);
                }
            }
            if (off < tolerance * tolerance) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double spq = s.get(p, q);
                    if (Math.abs(spq) < 1e-15) {
                        continue;
                    }
                    double spp = s.get(p, p);
                    double sqq = s.get(q, q);
                    double tau = (sqq - spp) / (2.0 * spq);
                    double t;
                    if (tau >= 0) {
                        t = 1.0 / (tau + Math.sqrt(1.0 + tau * tau));
                    } else {
                        t = -1.0 / (-tau + Math.sqrt(1.0 + tau * tau));
                    }
                    double cs = 1.0 / Math.sqrt(1.0 + t * t);
                    double sn = t * cs;
                    for (int k = 0; k < n; k++) {
                        double skp = s.get(k, p);
                        double skq = s.get(k, q);
                        s.set(k, p, cs * skp - sn * skq);
                        s.set(k, q, sn * skp + cs * skq);
                    }
                    for (int k = 0; k < n; k++) {
                        double spk = s.get(p, k);
                        double sqk = s.get(q, k);
                        s.set(p, k, cs * spk - sn * sqk);
                        s.set(q, k, sn * spk + cs * sqk);
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v.get(k, p);
                        double vkq = v.get(k, q);
                        v.set(k, p, cs * vkp - sn * vkq);
                        v.set(k, q, sn * vkp + cs * vkq);
                    }
                }
            }
        }
        double[] eig = new double[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            eig[i] = s.get(i, i);
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(eig[x], eig[y]));
        double[] vals = new double[n];
        Mat vecs = new Mat(n, n);
        for (int j = 0; j < n; j++) {
            vals[j] = eig[idx[j]];
            int src = idx[j];
            for (int i = 0; i < n; i++) {
                vecs.set(i, j, v.get(i, src));
            }
        }
        return new Eigh(vals, vecs);
    }

    public static final class Eigh {
        public final double[] values;
        public final Mat vectors;

        public Eigh(double[] values, Mat vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /** True if the matrix is symmetric positive-semidefinite down to {@code tol}. */
    public PsdReport psdReport(double tol) {
        Eigh e = eighSym(this);
        double min = e.values[0];
        boolean psd = min >= -tol;
        return new PsdReport(psd, min, e.values, e.vectors);
    }

    public static final class PsdReport {
        public final boolean psd;
        public final double minEigenvalue;
        public final double[] eigenvalues;
        public final Mat eigenvectors;

        public PsdReport(boolean psd, double minEigenvalue, double[] eigenvalues, Mat eigenvectors) {
            this.psd = psd;
            this.minEigenvalue = minEigenvalue;
            this.eigenvalues = eigenvalues;
            this.eigenvectors = eigenvectors;
        }
    }

    /** Inverse of a symmetric positive-definite matrix through its eigen basis. */
    public Mat inverseSymmetricPositiveDefinite() {
        Eigh e = eighSym(this);
        int n = r;
        Mat inv = new Mat(n, n);
        for (int k = 0; k < n; k++) {
            double iv = 1.0 / e.values[k];
            for (int i = 0; i < n; i++) {
                double vik = e.vectors.get(i, k);
                for (int j = 0; j < n; j++) {
                    inv.a[i * n + j] += vik * e.vectors.get(j, k) * iv;
                }
            }
        }
        return inv;
    }

    public String toPrettyString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < c; j++) {
                sb.append(String.format(Locale.ROOT, "%12.6f", get(i, j)));
                if (j + 1 < c) {
                    sb.append(' ');
                }
            }
            if (i + 1 < r) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
