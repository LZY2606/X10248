package lab.polarimetry.mmstudio.math;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.complex.ComplexField;
import org.apache.commons.math3.linear.*;

/** Pauli basis {sigma0, sigmax, sigmay, sigmaz} and Cloude coherency machinery. */
public final class Pauli {
    private Pauli() {}

    public static FieldMatrix<Complex>[] basis() {
        @SuppressWarnings("unchecked")
        FieldMatrix<Complex>[] s = new FieldMatrix[4];
        Complex z = Complex.ZERO, one = Complex.ONE, mi = new Complex(0, -1), ii = new Complex(0, 1);
        s[0] = new Array2DRowFieldMatrix<>(ComplexField.getInstance(), new Complex[][]{{one, z}, {z, one}});
        s[1] = new Array2DRowFieldMatrix<>(ComplexField.getInstance(), new Complex[][]{{z, one}, {one, z}});
        s[2] = new Array2DRowFieldMatrix<>(ComplexField.getInstance(), new Complex[][]{{z, mi}, {ii, z}});
        s[3] = new Array2DRowFieldMatrix<>(ComplexField.getInstance(), new Complex[][]{{one, z}, {z, one.negate()}});
        return s;
    }

    static FieldMatrix<Complex> mul(FieldMatrix<Complex> a, FieldMatrix<Complex> b) {
        return a.multiply(b);
    }

    static Complex tr(FieldMatrix<Complex> a) {
        Complex t = Complex.ZERO;
        for (int i = 0; i < a.getRowDimension(); i++) t = t.add(a.getEntry(i, i));
        return t;
    }

    /**
     * Cloude coherency matrix R (4x4 Hermitian) of a Mueller matrix M.
     * R_ij = 1/8 * sum_{a,b} M_ab Tr(sigma_i sigma_a sigma_j sigma_b).
     * Returned as an 8x8 real symmetric block form [[Re R, -Im R],[Im R, Re R]];
     * eigenvalues of R appear in duplicated pairs.
     */
    public static RealMatrix coherencyReal(RealMatrix mueller) {
        FieldMatrix<Complex>[] s = basis();
        Complex[][] r = new Complex[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Complex sum = Complex.ZERO;
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) {
                        Complex t = tr(mul(mul(mul(s[i], s[a]), s[j]), s[b]));
                        sum = sum.add(t.multiply(mueller.getEntry(a, b)));
                    }
                }
                r[i][j] = sum.divide(8);
            }
        }
        RealMatrix h = Mx.zeros(8, 8);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double re = r[i][j].getReal(), im = r[i][j].getImaginary();
                h.setEntry(i, j, re);
                h.setEntry(i + 4, j + 4, re);
                h.setEntry(i, j + 4, -im);
                h.setEntry(i + 4, j, im);
            }
        }
        return Mx.symm(h);
    }

    /** Unique eigenvalues of the Hermitian coherency matrix (4 values, ascending not guaranteed). */
    public static double[] coherencyEigenvalues(RealMatrix mueller) {
        EigenDecomposition e = new EigenDecomposition(coherencyReal(mueller));
        double[] all = e.getRealEigenvalues();
        double[] out = new double[4];
        boolean[] used = new boolean[8];
        for (int k = 0; k < 4; k++) {
            int pick = -1;
            for (int t = 0; t < 8; t++) {
                if (!used[t]) { pick = t; break; }
            }
            int mate = pick;
            for (int t = pick + 1; t < 8; t++) {
                if (!used[t] && Math.abs(all[t] - all[pick]) < 1e-9 * Math.max(1, Math.abs(all[pick]))) { mate = t; break; }
            }
            used[pick] = true; used[mate] = true;
            out[k] = (all[pick] + all[mate]) / 2.0;
        }
        return out;
    }

    /** Pure-component Mueller matrix from a Jones coefficient vector a (H = sum a_i sigma_i):
     *  M_ab = 1/2 Tr(sigma_a H sigma_b H^dagger). */
    public static RealMatrix pureMueller(Complex[] a) {
        FieldMatrix<Complex>[] s = basis();
        FieldMatrix<Complex> h = new Array2DRowFieldMatrix<>(ComplexField.getInstance(),
                new Complex[][]{{Complex.ZERO, Complex.ZERO}, {Complex.ZERO, Complex.ZERO}});
        for (int i = 0; i < 4; i++) h = h.add(s[i].scalarMultiply(a[i]));
        // Hermitian conjugate
        FieldMatrix<Complex> hc = h.copy();
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) hc.setEntry(i, j, h.getEntry(j, i).conjugate());
        RealMatrix m = Mx.zeros(4, 4);
        for (int alpha = 0; alpha < 4; alpha++) {
            for (int beta = 0; beta < 4; beta++) {
                Complex t = tr(mul(mul(mul(s[alpha], h), s[beta]), hc));
                m.setEntry(alpha, beta, t.divide(2).getReal());
            }
        }
        return m;
    }
}
