package polworkspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Matrix {
    public final int rows;
    public final int cols;
    public final double[][] a;

    private Matrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows][cols];
    }

    public static Matrix zeros(int rows, int cols) { return new Matrix(rows, cols); }
    public static Matrix identity(int n) {
        Matrix result = zeros(n, n);
        for (int i = 0; i < n; i++) result.a[i][i] = 1;
        return result;
    }
    public static Matrix of(double[][] values) {
        Matrix result = zeros(values.length, values[0].length);
        for (int i = 0; i < values.length; i++) result.a[i] = Arrays.copyOf(values[i], values[i].length);
        return result;
    }

    public double get(int row, int col) { return a[row][col]; }
    public Matrix copy() { return of(a); }

    public Matrix transpose() {
        Matrix result = zeros(cols, rows);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) result.a[c][r] = a[r][c];
        return result;
    }

    public Matrix multiply(Matrix other) {
        if (cols != other.rows) throw new IllegalArgumentException("Matrix dimensions do not match");
        Matrix result = zeros(rows, other.cols);
        for (int r = 0; r < rows; r++)
            for (int k = 0; k < cols; k++) {
                double x = a[r][k];
                if (x == 0) continue;
                for (int c = 0; c < other.cols; c++) result.a[r][c] += x * other.a[k][c];
            }
        return result;
    }

    public double[] multiply(double[] vector) {
        if (cols != vector.length) throw new IllegalArgumentException("Vector dimension does not match");
        double[] result = new double[rows];
        for (int r = 0; r < rows; r++) {
            double sum = 0;
            for (int c = 0; c < cols; c++) sum += a[r][c] * vector[c];
            result[r] = sum;
        }
        return result;
    }

    public Matrix add(Matrix other) {
        if (rows != other.rows || cols != other.cols) throw new IllegalArgumentException("Matrix dimensions do not match");
        Matrix result = zeros(rows, cols);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) result.a[r][c] = a[r][c] + other.a[r][c];
        return result;
    }

    public Matrix scale(double factor) {
        Matrix result = zeros(rows, cols);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) result.a[r][c] = factor * a[r][c];
        return result;
    }

    public double frobenius() {
        double sum = 0;
        for (double[] row : a) for (double x : row) sum += x * x;
        return Math.sqrt(sum);
    }

    public double[] column(int c) {
        double[] result = new double[rows];
        for (int r = 0; r < rows; r++) result[r] = a[r][c];
        return result;
    }

    public static Matrix fromColumn(double[] vector) {
        Matrix result = zeros(vector.length, 1);
        for (int i = 0; i < vector.length; i++) result.a[i][0] = vector[i];
        return result;
    }

    public static double[] solvePositiveDefinite(Matrix a, double[] b) {
        int n = a.rows;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a.a[i][j];
                for (int k = 0; k < j; k++) sum -= l[i][k] * l[j][k];
                if (i == j) {
                    if (sum <= 1.0e-14) throw new ArithmeticException("Matrix is not positive definite");
                    l[i][j] = Math.sqrt(sum);
                } else l[i][j] = sum / l[j][j];
            }
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = b[i];
            for (int k = 0; k < i; k++) sum -= l[i][k] * y[k];
            y[i] = sum / l[i][i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = y[i];
            for (int k = i + 1; k < n; k++) sum -= l[k][i] * x[k];
            x[i] = sum / l[i][i];
        }
        return x;
    }

    public static SymmetricEigen symmetricEigen(Matrix matrix) {
        if (matrix.rows != matrix.cols) throw new IllegalArgumentException("Symmetric eigen decomposition requires square matrix");
        int n = matrix.rows;
        Matrix v = identity(n);
        Matrix work = matrix.copy();
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += work.a[p][q] * work.a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = work.a[p][q];
                    if (Math.abs(apq) < 1e-15) continue;
                    double app = work.a[p][p];
                    double aqq = work.a[q][q];
                    double tau = (aqq - app) / (2 * apq);
                    double t;
                    if (tau >= 0) t = 1 / (tau + Math.sqrt(1 + tau * tau));
                    else t = -1 / (-tau + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    work.a[p][p] = app - t * apq;
                    work.a[q][q] = aqq + t * apq;
                    work.a[p][q] = work.a[q][p] = 0;
                    for (int k = 0; k < n; k++) {
                        if (k != p && k != q) {
                            double akp = work.a[k][p];
                            double akq = work.a[k][q];
                            work.a[k][p] = work.a[p][k] = c * akp - s * akq;
                            work.a[k][q] = work.a[q][k] = s * akp + c * akq;
                        }
                        double vkp = v.a[k][p];
                        double vkq = v.a[k][q];
                        v.a[k][p] = c * vkp - s * vkq;
                        v.a[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = work.a[i][i];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(values[y], values[x]));
        double[] sortedValues = new double[n];
        Matrix sortedVectors = zeros(n, n);
        for (int i = 0; i < n; i++) {
            sortedValues[i] = values[order[i]];
            for (int r = 0; r < n; r++) sortedVectors.a[r][i] = v.a[r][order[i]];
        }
        return new SymmetricEigen(sortedValues, sortedVectors);
    }

    public static Matrix inverse(Matrix matrix) {
        if (matrix.rows != matrix.cols) throw new IllegalArgumentException("Inverse requires square matrix");
        int n = matrix.rows;
        double[][] a = new double[n][2 * n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(matrix.a[r], 0, a[r], 0, n);
            a[r][n + r] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            if (Math.abs(a[pivot][col]) < 1e-15) throw new ArithmeticException("Matrix is singular");
            double[] swap = a[pivot]; a[pivot] = a[col]; a[col] = swap;
            double divisor = a[col][col];
            for (int c = 0; c < 2 * n; c++) a[col][c] /= divisor;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double factor = a[r][col];
                for (int c = 0; c < 2 * n; c++) a[r][c] -= factor * a[col][c];
            }
        }
        Matrix result = zeros(n, n);
        for (int r = 0; r < n; r++) System.arraycopy(a[r], n, result.a[r], 0, n);
        return result;
    }

    public record SymmetricEigen(double[] values, Matrix vectors) {
        public Matrix inverseSqrt(double floor) {
            int n = values.length;
            Matrix result = zeros(n, n);
            for (int i = 0; i < n; i++) {
                if (values[i] <= floor) throw new ArithmeticException("Covariance is singular at eigenvalue " + values[i]);
                double factor = 1 / Math.sqrt(values[i]);
                for (int r = 0; r < n; r++) result.a[r][i] = factor * vectors.a[r][i];
            }
            return result;
        }
    }

    public record RankAnalysis(double[] singularValues, int rank, double condition, Matrix nullSpace, double threshold) {}

    public static RankAnalysis rankAnalysis(Matrix design, double threshold) {
        Matrix gram = design.transpose().multiply(design);
        SymmetricEigen eig = symmetricEigen(gram);
        int p = gram.rows;
        double[] sv = new double[p];
        for (int i = 0; i < p; i++) sv[i] = Math.sqrt(Math.max(0, eig.values()[i]));
        int rank = 0;
        for (double x : sv) if (x > threshold) rank++;
        List<Integer> nullCols = new ArrayList<>();
        for (int i = rank; i < p; i++) if (sv[i] <= threshold) nullCols.add(i);
        Matrix nullSpace = zeros(p, nullCols.size());
        for (int j = 0; j < nullCols.size(); j++)
            for (int r = 0; r < p; r++) nullSpace.a[r][j] = eig.vectors().a[r][nullCols.get(j)];
        double condition = sv[0] / Math.max(sv[p - 1], 1e-300);
        return new RankAnalysis(sv, rank, condition, nullSpace, threshold);
    }

    public static LeastSquaresResult truncatedLeastSquares(Matrix design, double[] observations, Matrix covariance, double singularThreshold) {
        Matrix sqrtInfo;
        Matrix ad;
        double[] bd;
        int n = observations.length;
        if (covariance == null) {
            sqrtInfo = identity(n);
            ad = design;
            bd = observations;
        } else {
            SymmetricEigen ce = symmetricEigen(covariance);
            double floor = Math.max(1e-12 * ce.values()[0], 1e-18);
            Matrix whiten = zeros(n, n);
            for (int i = 0; i < n; i++) {
                if (ce.values()[i] <= floor) throw new ArithmeticException("Singular measurement covariance");
                double factor = 1 / Math.sqrt(ce.values()[i]);
                for (int r = 0; r < n; r++) whiten.a[r][i] = factor * ce.vectors().a[r][i];
            }
            sqrtInfo = whiten;
            ad = whiten.multiply(design);
            bd = whiten.multiply(observations);
        }
        Matrix normal = ad.transpose().multiply(ad);
        SymmetricEigen eig = symmetricEigen(normal);
        double[] values = eig.values();
        double cutoff = Math.max(singularThreshold, values[0] * 1e-12);
        int rank = 0;
        for (double value : values) if (Math.sqrt(Math.max(0, value)) > cutoff) rank++;
        double[] solution = new double[design.cols];
        double[][] covarianceOut = new double[design.cols][design.cols];
        Matrix rhs = ad.transpose().multiply(Matrix.fromColumn(bd));
        for (int i = 0; i < rank; i++) {
            double dot = 0;
            for (int r = 0; r < rhs.rows; r++) dot += eig.vectors().a[r][i] * rhs.a[r][0];
            double coefficient = dot / values[i];
            for (int r = 0; r < solution.length; r++) solution[r] += coefficient * eig.vectors().a[r][i];
            for (int r = 0; r < solution.length; r++) {
                for (int c = 0; c < solution.length; c++)
                    covarianceOut[r][c] += eig.vectors().a[r][i] * eig.vectors().a[c][i] / values[i];
            }
        }
        List<double[]> nullVectors = new ArrayList<>();
        for (int i = rank; i < values.length; i++) nullVectors.add(eig.vectors().column(i));
        Matrix nullSpace = Matrix.zeros(design.cols, nullVectors.size());
        for (int i = 0; i < nullVectors.size(); i++)
            for (int r = 0; r < design.cols; r++) nullSpace.a[r][i] = nullVectors.get(i)[r];
        double[] residual = new double[n];
        double[] predicted = design.multiply(solution);
        for (int i = 0; i < n; i++) residual[i] = observations[i] - predicted[i];
        double chi2 = 0;
        if (covariance == null) for (double r : residual) chi2 += r * r;
        else chi2 += dotProduct(residual, inverseMultiply(covariance, residual));
        double[] sv = Arrays.stream(values).map(v -> Math.sqrt(Math.max(0, v))).toArray();
        double condition = sv[0] / Math.max(sv[sv.length - 1], 1e-300);
        return new LeastSquaresResult(solution, Matrix.of(covarianceOut), rank, rank < design.cols, nullSpace, residual, chi2, condition, sv, sqrtInfo);
    }

    private static double dotProduct(double[] x, double[] y) {
        double sum = 0;
        for (int i = 0; i < x.length; i++) sum += x[i] * y[i];
        return sum;
    }

    private static double[] inverseMultiply(Matrix matrix, double[] vector) {
        SymmetricEigen eig = symmetricEigen(matrix);
        double floor = Math.max(1e-12 * eig.values()[0], 1e-18);
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            if (eig.values()[i] <= floor) continue;
            double dot = 0;
            for (int r = 0; r < vector.length; r++) dot += eig.vectors().a[r][i] * vector[r];
            double coefficient = dot / eig.values()[i];
            for (int r = 0; r < vector.length; r++) result[r] += coefficient * eig.vectors().a[r][i];
        }
        return result;
    }

    public record LeastSquaresResult(double[] solution, Matrix covariance, int rank, boolean underdetermined,
                                     Matrix nullSpace, double[] residual, double chiSquared,
                                     double condition, double[] singularValues, Matrix whitening) {}
}
