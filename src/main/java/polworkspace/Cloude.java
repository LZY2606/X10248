package polworkspace;

import java.util.Arrays;

public final class Cloude {
    private static final Complex[][][] PAULI = {
            {
                    {Complex.ONE, Complex.ZERO},
                    {Complex.ZERO, Complex.ONE}
            },
            {
                    {Complex.ONE, Complex.ZERO},
                    {Complex.ZERO, Complex.ONE.negate()}
            },
            {
                    {Complex.ZERO, Complex.ONE},
                    {Complex.ONE, Complex.ZERO}
            },
            {
                    {Complex.ZERO, Complex.I.negate()},
                    {Complex.I, Complex.ZERO}
            }
    };

    private Cloude() {}

    public static double[] eigenvalues(double[][] mueller) {
        Complex[][] coherency = new Complex[4][4];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) coherency[r][c] = Complex.ZERO;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Complex[][] term = tensor(PAULI[i], conjugate(PAULI[j]));
                double factor = mueller[i][j] / 2.0;
                for (int r = 0; r < 4; r++)
                    for (int c = 0; c < 4; c++) coherency[r][c] = coherency[r][c].add(term[r][c].scale(factor));
            }
        }
        Matrix realSymmetric = Matrix.zeros(8, 8);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                double real = (coherency[r][c].real + coherency[c][r].real) / 2;
                double imag = (coherency[r][c].imag - coherency[c][r].imag) / 2;
                realSymmetric.a[r][c] = real;
                realSymmetric.a[r + 4][c + 4] = real;
                realSymmetric.a[r][c + 4] = -imag;
                realSymmetric.a[r + 4][c] = imag;
            }
        }
        double[] values = Matrix.symmetricEigen(realSymmetric).values();
        Arrays.sort(values);
        return new double[] {values[7], values[5], values[3], values[1]};
    }

    private static Complex[][] tensor(Complex[][] left, Complex[][] right) {
        Complex[][] result = new Complex[4][4];
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
            for (int c = 0; c < 2; c++) for (int d = 0; d < 2; d++)
                result[2 * a + c][2 * b + d] = left[a][b].multiply(right[c][d]);
        return result;
    }

    private static Complex[][] conjugate(Complex[][] matrix) {
        Complex[][] result = new Complex[matrix.length][matrix[0].length];
        for (int r = 0; r < matrix.length; r++)
            for (int c = 0; c < matrix[r].length; c++) result[r][c] = matrix[r][c].conjugate();
        return result;
    }

    record Complex(double real, double imag) {
        static final Complex ZERO = new Complex(0, 0);
        static final Complex ONE = new Complex(1, 0);
        static final Complex I = new Complex(0, 1);
        Complex add(Complex other) { return new Complex(real + other.real, imag + other.imag); }
        Complex multiply(Complex other) { return new Complex(real * other.real - imag * other.imag, real * other.imag + imag * other.real); }
        Complex scale(double factor) { return new Complex(real * factor, imag * factor); }
        Complex negate() { return scale(-1); }
        Complex conjugate() { return new Complex(real, -imag); }
    }
}
