package com.polarlab.math;

import java.util.Arrays;
import java.util.Locale;

/** Dense real matrix in column-major-free row-major layout: a[row][col]. */
public final class Mat {
    public final int rows, cols;
    public final double[][] a;

    public Mat(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows][cols];
    }

    public static Mat of(double[][] data) {
        Mat m = new Mat(data.length, data[0].length);
        for (int r = 0; r < m.rows; r++) {
            System.arraycopy(data[r], 0, m.a[r], 0, m.cols);
        }
        return m;
    }

    public static Mat zeros(int r, int c) {
        return new Mat(r, c);
    }

    public static Mat identity(int n) {
        Mat m = new Mat(n, n);
        for (int i = 0; i < n; i++) m.a[i][i] = 1;
        return m;
    }

    public double get(int r, int c) {
        return a[r][c];
    }

    public Mat copy() {
        return Mat.of(a);
    }

    public Mat transpose() {
        Mat t = new Mat(cols, rows);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                t.a[c][r] = a[r][c];
        return t;
    }

    public Mat mul(Mat o) {
        if (cols != o.rows) throw new IllegalArgumentException("shape mismatch " + dim() + " x " + o.dim());
        Mat r = new Mat(rows, o.cols);
        for (int i = 0; i < rows; i++)
            for (int k = 0; k < cols; k++) {
                double aik = a[i][k];
                for (int j = 0; j < o.cols; j++) r.a[i][j] += aik * o.a[k][j];
            }
        return r;
    }

    public double[] mul(double[] v) {
        if (v.length != cols) throw new IllegalArgumentException("shape mismatch");
        double[] r = new double[rows];
        for (int i = 0; i < rows; i++) {
            double s = 0;
            for (int j = 0; j < cols; j++) s += a[i][j] * v[j];
            r[i] = s;
        }
        return r;
    }

    public Mat mul(double s) {
        Mat r = new Mat(rows, cols);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) r.a[i][j] = a[i][j] * s;
        return r;
    }

    public Mat add(Mat o) {
        Mat r = new Mat(rows, cols);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) r.a[i][j] = a[i][j] + o.a[i][j];
        return r;
    }

    public Mat sub(Mat o) {
        Mat r = new Mat(rows, cols);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) r.a[i][j] = a[i][j] - o.a[i][j];
        return r;
    }

    public Mat sliceRows(int... idx) {
        Mat r = new Mat(idx.length, cols);
        for (int i = 0; i < idx.length; i++)
            System.arraycopy(a[idx[i]], 0, r.a[i], 0, cols);
        return r;
    }

    /** Reorder columns by the given permutation of column indices (Stokes order change). */
    public Mat permuteColumns(int[] perm) {
        Mat r = new Mat(rows, cols);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) r.a[i][j] = a[i][perm[j]];
    return r;
    }

    /** Reorder rows by permutation. */
    public Mat permuteRows(int[] perm) {
        Mat r = new Mat(rows, cols);
        for (int i = 0; i < rows; i++)
            System.arraycopy(a[perm[i]], 0, r.a[i], 0, cols);
        return r;
    }

    public double frobenius() {
        double s = 0;
        for (double[] row : a) for (double x : row) s += x * x;
        return Math.sqrt(s);
    }

    public String dim() {
        return rows + "x" + cols;
    }

    public double[][] toArray() {
        double[][] out = new double[rows][cols];
        for (int i = 0; i < rows; i++) out[i] = Arrays.copyOf(a[i], cols);
        return out;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) sb.append(String.format(Locale.ROOT, "%12.6f ", a[i][j]));
            sb.append('\n');
        }
        return sb.toString();
    }
}
