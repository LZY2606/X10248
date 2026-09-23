package app.polar.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear algebra helpers for 4x4 Mueller work: row-major vec convention,
 * Kronecker design rows, SVD rank/condition/null-space and Hermitian spectra
 * obtained by realification (no external complex eigensolver needed).
 */
public final class Lin {
    public static final double SING_TOL = 1.0e-9;

    private Lin() {
    }

    /** Row-major vec: element (i,j) maps to index 4*i + j. */
    public static double[] vecRowMajor(RealMatrix m) {
        double[] out = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                out[4 * i + j] = m.getEntry(i, j);
            }
        }
        return out;
    }

    public static RealMatrix unflattenRowMajor(double[] v) {
        if (v.length != 16) {
            throw new IllegalArgumentException("expected 16 coefficients, got " + v.length);
        }
        RealMatrix m = new Array2DRowRealMatrix(4, 4);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                m.setEntry(i, j, v[4 * i + j]);
            }
        }
        return m;
    }

    /** Row of the measurement design matrix: (g kron a)^T under row-major vec. */
    public static double[] designRow(double[] generatorStokes, double[] analyzerStokes) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                row[4 * i + j] = generatorStokes[i] * analyzerStokes[j];
            }
        }
        return row;
    }

    public static RealMatrix matrix(double[][] rows) {
        return new Array2DRowRealMatrix(rows);
    }

    public record SvdInspection(double[] singularValues, double conditionNumber, int rank,
                                int nullity, double threshold, double[][] nullspace,
                                boolean exactSingular, boolean nearSingular) {
    }

    /**
     * Inspects a design matrix without forcing an inverse. Rank deficit means the
     * Mueller vector is not uniquely determined; callers must keep the candidate
     * family instead of pretending a unique answer exists.
     */
    public static SvdInspection inspect(RealMatrix w) {
        SingularValueDecomposition svd = new SingularValueDecomposition(w);
        double[] s = svd.getSingularValues();
        double smax = s[0];
        double threshold = Math.max(w.getRowDimension(), w.getColumnDimension()) * smax * SING_TOL;
        int rank = 0;
        List<double[]> nullBasis = new ArrayList<>();
        RealMatrix v = svd.getV();
        for (int k = 0; k < s.length; k++) {
            if (s[k] > threshold) {
                rank++;
            } else {
                nullBasis.add(v.getColumn(k));
            }
        }
        double smin = s[s.length - 1];
        double cond = smin <= 0 ? Double.POSITIVE_INFINITY : smax / smin;
        int nullity = w.getColumnDimension() - rank;
        boolean near = !Double.isInfinite(cond) && cond > 1.0e6;
        return new SvdInspection(s.clone(), cond, rank, nullity, threshold,
                nullBasis.toArray(new double[0][]), rank < w.getColumnDimension(), near);
    }

    /**
     * Weighted least squares m = (W^T C^-1 W)^-1 W^T C^-1 b solved with SVD.
     * Returns null when the normal-equation matrix is rank deficient; callers
     * must surface UNDERDETERMINED rather than forcing an inverse.
     */
    public static WeightedSolution weightedSolve(RealMatrix w, double[] b, RealMatrix covariance) {
        RealMatrix cInv = inverseSymmetric(covariance);
        RealMatrix wtc = w.transpose().multiply(cInv);
        RealMatrix normal = wtc.multiply(w);
        RealMatrix rhs = wtc.operate(b);
        SingularValueDecomposition svd = new SingularValueDecomposition(normal);
        double[] s = svd.getSingularValues();
        double smax = s[0];
        double threshold = 16 * smax * SING_TOL;
        int rank = 0;
        for (double v : s) {
            if (v > threshold) {
                rank++;
            }
        }
        if (rank < 16) {
            return null;
        }
        double[] m = new Solver(svd).solve(rhs);
        RealMatrix covM = inverseSymmetric(normal, svd);
        double[] sigma = new double[16];
        for (int i = 0; i < 16; i++) {
            sigma[i] = Math.sqrt(Math.max(0.0, covM.getEntry(i, i)));
        }
        double cond = s[s.length - 1] <= 0 ? Double.POSITIVE_INFINITY : smax / s[s.length - 1];
        return new WeightedSolution(m, covM.getData(), sigma, rank, cond);
    }

    public record WeightedSolution(double[] coefficients, double[][] covariance,
                                   double[] sigma, int rank, double conditionNumber) {
    }

    private static RealMatrix inverseSymmetric(RealMatrix a) {
        return inverseSymmetric(a, new SingularValueDecomposition(a));
    }

    private static RealMatrix inverseSymmetric(RealMatrix a, SingularValueDecomposition svd) {
        int n = a.getRowDimension();
        RealMatrix inv = new Array2DRowRealMatrix(n, n);
        RealMatrix u = svd.getU();
        RealMatrix v = svd.getV();
        double[] s = svd.getSingularValues();
        for (int k = 0; k < n; k++) {
            if (s[k] <= n * s[0] * SING_TOL) {
                throw new ArithmeticException("singular covariance/normal matrix at index " + k);
            }
            double invS = 1.0 / s[k];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    inv.addToEntry(i, j, v.getEntry(i, k) * invS * u.getEntry(j, k));
                }
            }
        }
        return inv;
    }

    private record Solver(SingularValueDecomposition svd) {
        double[] solve(double[] rhs) {
            RealMatrix u = svd.getU();
            RealMatrix v = svd.getV();
            double[] s = svd.getSingularValues();
            int n = s.length;
            double[] tmp = new double[n];
            for (int k = 0; k < n; k++) {
                double dot = 0;
                for (int i = 0; i < n; i++) {
                    dot += u.getEntry(i, k) * rhs[i];
                }
                tmp[k] = dot / s[k];
            }
            double[] out = new double[n];
            for (int k = 0; k < n; k++) {
                for (int i = 0; i < n; i++) {
                    out[i] += v.getEntry(i, k) * tmp[k];
                }
            }
            return out;
        }
    }

    /**
     * Eigen-decomposition of a Hermitian matrix H = re + i*im (im antisymmetric)
     * through the symmetric realification [[re,-im],[im,re]]; eigenvalues occur
     * in pairs. Returns real eigenvalues and complex eigenvectors.
     */
    public record HermitianSpectrum(double[] eigenvalues, double[][][] eigenvectorsReal,
                                    double[][][] eigenvectorsImag) {
    }

    public static HermitianSpectrum hermitianEigen(double[][] re, double[][] im) {
        int n = re.length;
        RealMatrix c = new Array2DRowRealMatrix(2 * n, 2 * n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                c.setEntry(i, j, re[i][j]);
                c.setEntry(i, j + n, -im[i][j]);
                c.setEntry(i + n, j, im[i][j]);
                c.setEntry(i + n, j + n, re[i][j]);
            }
        }
        org.apache.commons.math3.linear.EigenDecomposition eig =
                new org.apache.commons.math3.linear.EigenDecomposition(c);
        List<double[]> vals = new ArrayList<>();
        List<double[]> vecs = new ArrayList<>();
        for (int k = 0; k < 2 * n; k++) {
            double imEig = eig.getImagEigenvalue(k);
            if (Math.abs(imEig) > 1e-8) {
                continue;
            }
            double[] v = eig.getEigenvector(k).toArray();
            boolean canonical = false;
            for (int t = 0; t < n; t++) {
                if (Math.abs(v[t]) > 1e-9 || Math.abs(v[t + n]) > 1e-9) {
                    canonical = v[t] > 0 || (v[t] == 0 && v[t + n] > 0);
                    break;
                }
            }
            double lambda = eig.getRealEigenvalue(k);
            boolean duplicate = false;
            for (double[] prev : vals) {
                if (Math.abs(prev[0] - lambda) < 1e-9) {
                    duplicate = true;
                    break;
                }
            }
            if (canonical && !duplicate) {
                vals.add(new double[]{lambda});
                vecs.add(v);
            }
        }
        int cnt = vals.size();
        double[] lambdas = new double[cnt];
        double[][] vr = new double[cnt][n];
        double[][] vi = new double[cnt][n];
        for (int i = 0; i < cnt; i++) {
            lambdas[i] = vals.get(i)[0];
            for (int t = 0; t < n; t++) {
                vr[i][t] = vecs.get(i)[t];
                vi[i][t] = vecs.get(i)[t + n];
            }
        }
        return new HermitianSpectrum(lambdas, vr, vi);
    }

    public static RealMatrix identity(int n) {
        return MatrixUtils.createRealIdentityMatrix(n);
    }
}
