package pol.math;

import java.util.Locale;

/** Immutable dense 4x4 real matrix in column-free row-major storage. */
public final class Mat4 {
    public final double[][] a;

    public Mat4(double[][] m) {
        if (m.length != 4) throw new IllegalArgumentException("need 4 rows");
        this.a = new double[4][4];
        for (int i = 0; i < 4; i++) {
            if (m[i].length != 4) throw new IllegalArgumentException("need 4 cols");
            System.arraycopy(m[i], 0, a[i], 0, 4);
        }
    }

    public static Mat4 identity() {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) m[i][i] = 1;
        return new Mat4(m);
    }

    public double get(int r, int c) { return a[r][c]; }

    public double[] mul(double[] v) {
        double[] o = new double[4];
        for (int i = 0; i < 4; i++) {
            double s = 0;
            for (int j = 0; j < 4; j++) s += a[i][j] * v[j];
            o[i] = s;
        }
        return o;
    }

    public Mat4 mul(Mat4 b) {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[i][k] * b.a[k][j];
                o[i][j] = s;
            }
        return new Mat4(o);
    }

    public Mat4 transpose() {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) o[j][i] = a[i][j];
        return new Mat4(o);
    }

    public Mat4 scale(double s) {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) o[i][j] = a[i][j] * s;
        return new Mat4(o);
    }

    public Mat4 plus(Mat4 b) {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) o[i][j] = a[i][j] + b.a[i][j];
        return new Mat4(o);
    }

    public Mat4 minus(Mat4 b) { return plus(b.scale(-1)); }

    public double trace() { return a[0][0] + a[1][1] + a[2][2] + a[3][3]; }

    public double frob2() {
        double s = 0;
        for (double[] row : a) for (double v : row) s += v * v;
        return s;
    }

    public double[] flatRowMajor() {
        double[] f = new double[16];
        for (int i = 0; i < 4; i++) System.arraycopy(a[i], 0, f, i * 4, 4);
        return f;
    }

    public static Mat4 fromFlat(double[] f) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) System.arraycopy(f, i * 4, m[i], 0, 4);
        return new Mat4(m);
    }

    public Mat4 inverse() {
        double[][] inv = new double[4][4];
        for (int i = 0; i < 4; i++) inv[i][i] = 1;
        double[][] w = new double[4][4];
        for (int i = 0; i < 4; i++) System.arraycopy(a[i], 0, w[i], 0, 4);
        for (int col = 0; col < 4; col++) {
            int pivot = col;
            for (int r = col + 1; r < 4; r++) if (Math.abs(w[r][col]) > Math.abs(w[pivot][col])) pivot = r;
            if (Math.abs(w[pivot][col]) < 1e-14) throw new ArithmeticException("singular 4x4 matrix, refusing to invert");
            double[] tmp = w[col]; w[col] = w[pivot]; w[pivot] = tmp;
            tmp = inv[col]; inv[col] = inv[pivot]; inv[pivot] = tmp;
            double d = w[col][col];
            for (int j = 0; j < 4; j++) { w[col][j] /= d; inv[col][j] /= d; }
            for (int r = 0; r < 4; r++) if (r != col) {
                double f = w[r][col];
                for (int j = 0; j < 4; j++) { w[r][j] -= f * w[col][j]; inv[r][j] -= f * inv[col][j]; }
            }
        }
        // inv rows hold the solution rows because we eliminated treating columns; transpose-free convention:
        // We performed row operations on w and same on inv; result is inv such that w*inv=I only if we swapped identity orientation.
        return new Mat4(inv);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (double[] r : a) {
            for (double v : r) sb.append(String.format(Locale.ROOT, "%9.4f ", v));
            sb.append('\n');
        }
        return sb.toString();
    }
}
