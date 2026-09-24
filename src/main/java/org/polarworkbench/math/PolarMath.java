package org.polarworkbench.math;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.List;

public final class PolarMath {
    public static final String[] DEFAULT_STOKES = {"I", "Q", "U", "V"};

    private PolarMath() {
    }

    public static double[] stokesFromRaw(String name, double angleDegrees, String signConvention,
                                         String stokesOrder, String unit) {
        double angle = Math.toRadians(angleDegrees) * ("MINUS_THETA".equals(signConvention) ? -1.0 : 1.0);
        double[] canonical = switch (name == null ? "" : name.toUpperCase()) {
            case "H" -> new double[]{1, 1, 0, 0};
            case "V" -> new double[]{1, -1, 0, 0};
            case "P", "+45" -> new double[]{1, 0, Math.cos(2 * angle) == 0 ? 1 : Math.cos(2 * angle), 0};
            case "M", "-45" -> new double[]{1, 0, -1, 0};
            case "R" -> new double[]{1, 0, 0, 1};
            case "L" -> new double[]{1, 0, 0, -1};
            default -> {
                double q = Math.cos(2 * angle);
                double u = Math.sin(2 * angle);
                yield new double[]{1, q, u, 0};
            }
        };
        return reorderCanonical(canonical, stokesOrder);
    }

    public static double[] reorderCanonical(double[] canonical, String stokesOrder) {
        if (stokesOrder == null || stokesOrder.isBlank() || String.join(",", DEFAULT_STOKES).equals(stokesOrder)) {
            return canonical;
        }
        String[] order = stokesOrder.split(",");
        double[] result = new double[4];
        for (int index = 0; index < 4; index++) {
            int source = java.util.Arrays.asList(DEFAULT_STOKES).indexOf(order[index].trim());
            result[index] = canonical[source];
        }
        return result;
    }

    public static double[] canonicalFromOrdered(double[] ordered, String stokesOrder) {
        if (stokesOrder == null || stokesOrder.isBlank() || String.join(",", DEFAULT_STOKES).equals(stokesOrder)) {
            return ordered;
        }
        String[] order = stokesOrder.split(",");
        double[] result = new double[4];
        for (int index = 0; index < 4; index++) {
            int source = java.util.Arrays.asList(DEFAULT_STOKES).indexOf(order[index].trim());
            result[source] = ordered[index];
        }
        return result;
    }

    public static RealMatrix designMatrix(List<double[]> generators, List<double[]> analyzers) {
        double[][] rows = new double[generators.size() * analyzers.size()][16];
        int row = 0;
        for (double[] generator : generators) {
            for (double[] analyzer : analyzers) {
                int column = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        rows[row][column++] = analyzer[i] * generator[j];
                    }
                }
                row++;
            }
        }
        return new Array2DRowRealMatrix(rows, false);
    }

    public static RealMatrix scaleMueller(RealMatrix mueller, RealMatrix instrument, Double referenceTransmittance) {
        RealMatrix corrected = instrument.copy().multiply(mueller);
        if (referenceTransmittance != null && referenceTransmittance > 0) {
            corrected = corrected.scalarMultiply(1.0 / referenceTransmittance);
        }
        return corrected;
    }

    public static double[] normalizeCovariance(RealMatrix covariance, double gainPerCountSquared) {
        return null;
    }

    public static PhysicalReport physicalReport(RealMatrix mueller) {
        Complex[][][] coherency = coherencyMatrix(mueller);
        double[] eigenvalues = hermitianEigenvalues(coherency);
        List<double[]> violations = new ArrayList<>();
        double minEigen = Double.POSITIVE_INFINITY;
        for (int index = 0; index < eigenvalues.length; index++) {
            minEigen = Math.min(minEigen, eigenvalues[index]);
            if (eigenvalues[index] < -1.0e-8) {
                violations.add(new double[]{index, eigenvalues[index]});
            }
        }
        double[] firstColumn = mueller.getColumn(0);
        double[] passiveViolations = new double[firstColumn.length];
        for (int index = 0; index < firstColumn.length; index++) {
            passiveViolations[index] = Math.min(0.0, firstColumn[index]);
        }
        boolean physicallyRealizable = violations.isEmpty() && firstColumn[0] >= 0.0;
        double m00 = mueller.getEntry(0, 0);
        double normalizedMax = 1.0;
        double normalizedViolation = Math.max(0.0, Math.abs(mueller.getEntry(0, 1)) + Math.abs(mueller.getEntry(0, 2))
                + Math.abs(mueller.getEntry(0, 3)) - m00);
        return new PhysicalReport(coherency, eigenvalues, physicallyRealizable, minEigen,
                passiveViolations, normalizedMax, normalizedViolation);
    }

    public record PhysicalReport(Complex[][][] coherency, double[] eigenvalues, boolean physicallyRealizable,
                                 double minimumEigenvalue, double[] passiveFirstColumnResiduals,
                                 double normalizedMaximum, double normalizedResidual) {
    }

    public static Complex[][][] coherencyMatrix(RealMatrix m) {
        org.apache.commons.math3.complex.FieldMatrix<Complex> basis =
                new org.apache.commons.math3.complex.Array2DRowFieldMatrix<>(new Complex[16][16]);
        Complex[][][] pauli = complexPauli();
        for (int k = 0; k < 4; k++) {
            for (int l = 0; l < 4; l++) {
                int row = 4 * k + l;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        Complex trace = trace(multiply(pauli[k], multiply(pauli[i], multiply(pauli[l], pauli[j]))));
                        basis.setEntry(row, 4 * i + j, trace.divide(2.0));
                    }
                }
            }
        }
        org.apache.commons.math3.complex.FieldMatrix<Complex> inverse =
                new org.apache.commons.math3.linear.FieldLUDecomposition<>(basis).getSolver().getInverse();
        Complex[] flat = new Complex[16];
        int index = 0;
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                flat[index++] = Complex.valueOf(m.getEntry(row, column));
            }
        }
        Complex[] parameters = inverse.operate(flat);
        Complex[][] h = new Complex[4][4];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                h[row][column] = parameters[4 * row + column];
            }
        }
        return h;
    }

    private static Complex[][][] complexPauli() {
        return new Complex[][][]{
                {{Complex.ONE, Complex.ZERO}, {Complex.ZERO, Complex.ONE}},
                {{Complex.ONE, Complex.ZERO}, {Complex.ZERO, Complex.ONE.negate()}},
                {{Complex.ZERO, Complex.ONE}, {Complex.ONE, Complex.ZERO}},
                {{Complex.ZERO, Complex.ONE.negate()}, {Complex.ONE, Complex.ZERO}}
        };
    }

    private static Complex[][][] multiply(Complex[][][] left, Complex[][][] right) {
        Complex[][][] result = new Complex[2][2][];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                Complex sum = Complex.ZERO;
                for (int k = 0; k < 2; k++) {
                    sum = sum.add(left[i][k].multiply(right[k][j]));
                }
                result[i][j] = new Complex[]{sum};
            }
        }
        Complex[][][] normal = new Complex[2][2][];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                normal[i][j] = result[i][j][0];
            }
        }
        return normal;
    }

    private static Complex trace(Complex[][][] matrix) {
        return matrix[0][0].add(matrix[1][1]);
    }

    private static double[] hermitianEigenvalues(Complex[][][] h) {
        double[][] block = new double[8][8];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                Complex value = h[row][column];
                block[row][column] = value.getReal();
                block[row + 4][column + 4] = value.getReal();
                block[row][column + 4] = -value.getImaginary();
                block[row + 4][column] = value.getImaginary();
            }
        }
        EigenDecomposition eigen = new EigenDecomposition(new Array2DRowRealMatrix(block));
        double[] unique = new double[4];
        System.arraycopy(eigen.getRealEigenvalues(), 0, unique, 0, 4);
        java.util.Arrays.sort(unique);
        return unique;
    }

    public static java.util.List<DecompositionCandidate> decompositions(RealMatrix mueller) {
        return java.util.List.of(
                new DecompositionCandidate("normalized_m00", "M/m00", normalized(mueller),
                        List.of("normalization", "display")),
                new DecompositionCandidate("polar_forward", "H_forward", polar(mueller, false),
                        List.of("matrix_polar", "forward")),
                new DecompositionCandidate("polar_reverse", "H_reverse", polar(mueller, true),
                        List.of("matrix_polar", "reverse"))
        );
    }

    public record DecompositionCandidate(String stableId, String formula, double[][] matrix,
                                         java.util.List<String> labels) {
    }

    private static RealMatrix normalized(RealMatrix m) {
        double scale = Math.abs(m.getEntry(0, 0)) < 1e-14 ? 1.0 : m.getEntry(0, 0);
        return m.scalarMultiply(1.0 / scale);
    }

    private static RealMatrix polar(RealMatrix m, boolean reverse) {
        RealMatrix working = reverse ? m.transpose() : m;
        EigenDecomposition eigen = new EigenDecomposition(working.multiply(working.transpose()));
        RealMatrix h = MatrixUtils.createRealMatrix(4, 4);
        for (int index = 0; index < 4; index++) {
            double eigenvalue = Math.max(eigen.getRealEigenvalue(index), 0.0);
            h = h.add(eigen.getEigenvector(index).outerProduct(eigen.getEigenvector(index))
                    .scalarMultiply(Math.sqrt(eigenvalue)));
        }
        RealMatrix result = h;
        return reverse ? result.transpose() : result;
    }
}
