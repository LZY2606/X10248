package org.polarworkbench.math;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Matrices {
    public static final double SINGULAR_VALUE_REL_EPS = 1.0e-10;
    public static final double NEAR_SINGULAR_CONDITION = 1.0e8;

    private Matrices() {
    }

    public static RealMatrix matrix(double[][] values) {
        return new Array2DRowRealMatrix(values);
    }

    public static double[][] values(RealMatrix matrix) {
        return matrix.getData();
    }

    public static double[] flatten(RealMatrix matrix) {
        double[][] data = matrix.getData();
        double[] result = new double[data.length * data[0].length];
        for (int row = 0; row < data.length; row++) {
            System.arraycopy(data[row], 0, result, row * data[row].length, data[row].length);
        }
        return result;
    }

    public static RealMatrix unflatten(double[] values, int rows, int columns) {
        double[][] data = new double[rows][columns];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(values, row * columns, data[row], 0, columns);
        }
        return new Array2DRowRealMatrix(data, false);
    }

    public record LeastSquaresResult(double[] solution, RealMatrix covariance, double condition,
                                     int rank, double[] singularValues, double[][] nullBasis,
                                     boolean nearSingular, boolean rankDeficient,
                                     double effectiveSingularThreshold, List<String> diagnostics) {
    }

    public static LeastSquaresResult weightedLeastSquares(RealMatrix design, double[] response,
                                                          RealMatrix covariance) {
        List<String> diagnostics = new ArrayList<>();
        SingularValueDecomposition covarianceSvd = new SingularValueDecomposition(covariance);
        double[] covarianceSingular = covarianceSvd.getSingularValues();
        double covarianceFloor = Math.max(1.0e-12, covarianceSingular[0] * 1.0e-10);
        boolean covariancePositive = Arrays.stream(covarianceSingular).allMatch(v -> v > covarianceFloor);
        RealMatrix sqrtPrecision;
        if (covariancePositive) {
            sqrtPrecision = inverseSquareRoot(covariance);
            diagnostics.add("covariance_whitened");
        } else {
            sqrtPrecision = MatrixUtils.createRealIdentityMatrix(design.getRowDimension());
            diagnostics.add("covariance_not_positive_definite_fallback_ols");
        }

        RealMatrix whitenedDesign = sqrtPrecision.multiply(design);
        double[] whitenedResponse = sqrtPrecision.operate(response);
        SingularValueDecomposition svd = new SingularValueDecomposition(whitenedDesign);
        double[] singularValues = svd.getSingularValues();
        double largest = singularValues.length == 0 ? 0.0 : singularValues[0];
        double threshold = largest * SINGULAR_VALUE_REL_EPS;
        int rank = 0;
        for (double value : singularValues) {
            if (value > threshold) {
                rank++;
            }
        }
        boolean rankDeficient = rank < Math.min(design.getRowDimension(), design.getColumnDimension());
        double smallestKept = rank == 0 ? Double.POSITIVE_INFINITY : singularValues[rank - 1];
        double condition = rank == 0 ? Double.POSITIVE_INFINITY : largest / smallestKept;
        boolean nearSingular = !rankDeficient && condition > NEAR_SINGULAR_CONDITION;
        if (rankDeficient) {
            diagnostics.add("rank_deficient_design_pseudoinverse_only_no_forced_inverse");
        }
        if (nearSingular) {
            diagnostics.add("near_singular_design_covariance_marked_unreliable");
        }

        double[] solution = svd.getSolver().solve(whitenedResponse);
        RealMatrix covarianceOut;
        if (!rankDeficient && !nearSingular) {
            RealMatrix inverse = new QRDecomposition(whitenedDesign).getSolver().getInverse();
            covarianceOut = inverse.multiply(inverse.transpose());
        } else {
            covarianceOut = null;
        }

        RealMatrix v = svd.getV();
        List<double[]> nullVectors = new ArrayList<>();
        for (int column = rank; column < v.getColumnDimension(); column++) {
            nullVectors.add(v.getColumn(column));
        }
        return new LeastSquaresResult(solution, covarianceOut, condition, rank, singularValues,
                nullVectors.toArray(double[][]::new), nearSingular, rankDeficient, threshold, diagnostics);
    }

    public static RealMatrix inverseSquareRoot(RealMatrix symmetricPositive) {
        EigenDecomposition eigen = new EigenDecomposition(symmetricPositive);
        RealMatrix result = MatrixUtils.createRealMatrix(symmetricPositive.getRowDimension(),
                symmetricPositive.getColumnDimension());
        for (int index = 0; index < symmetricPositive.getColumnDimension(); index++) {
            double eigenvalue = eigen.getRealEigenvalue(index);
            RealVector vector = eigen.getEigenvector(index);
            double scale = 1.0 / Math.sqrt(Math.max(eigenvalue, 1.0e-18));
            result = result.add(vector.outerProduct(vector).scalarMultiply(scale));
        }
        return result;
    }

    public static double frobenius(RealMatrix matrix) {
        return Math.sqrt(matrix.transpose().multiply(matrix).getTrace());
    }

    public static boolean isPositiveDefinite(RealMatrix matrix) {
        try {
            new CholeskyDecomposition(matrix, 1.0e-8, 1.0e-10);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

}
