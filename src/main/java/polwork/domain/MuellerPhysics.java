package polwork.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/**
 * Physical-realizability testing and canonical decompositions for Mueller matrices.
 *
 * Realizability (Cloude): a Mueller matrix M is realizable by a linear optical
 * element iff the 4x4 Hermitian coherency matrix
 *   T = (1/2) sum_ij M_ij (sigma_i (kron) sigma_j*)
 * is positive semidefinite. Violations are reported and attached to the
 * candidate; this class never projects T back to the PSD cone and never
 * presents a projected matrix as the estimated solution.
 */
public final class MuellerPhysics {

    private MuellerPhysics() {
    }

    public static final double REALIZABILITY_TOLERANCE = 1e-9;

    /** Minimal double precision helper for the Pauli algebra below. */
    private record C(double re, double im) {
        C add(C o) {
            return new C(re + o.re, im + o.im);
        }

        C mul(C o) {
            return new C(re * o.re - im * o.im, re * o.im + im * o.re);
        }

        C conj() {
            return new C(re, -im);
        }

        static C tr(C[][] a) {
            C s = new C(0, 0);
            for (int i = 0; i < a.length; i++) {
                s = s.add(a[i][i]);
            }
            return s;
        }
    }

    private static final C[][][] PAULI = new C[4][2][2];

    static {
        C z = new C(0, 0);
        C one = new C(1, 0);
        C i = new C(0, 1);
        PAULI[0] = new C[][] {{one, z}, {z, one}};
        PAULI[1] = new C[][] {{one, z}, {z, new C(-1, 0)}};
        PAULI[2] = new C[][] {{z, one}, {one, z}};
        PAULI[3] = new C[][] {{z, new C(0, -1)}, {i, z}};
    }

    /**
     * Builds the 4x4 Hermitian Cloude coherency matrix (real and imaginary parts).
     * T = (1/2) sum_ij M_ij (sigma_i (kron) sigma_j*) in the column-vec Jones
     * basis, with Pauli order I, sigmaz(Q), sigmax(U), sigmay(V).
     */
    public static void coherencyMatrix(double[][] m, double[][] reOut, double[][] imOut) {
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                C sum = new C(0, 0);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        for (int r2 = 0; r2 < 2; r2++) {
                            for (int c2 = 0; c2 < 2; c2++) {
                                for (int r1 = 0; r1 < 2; r1++) {
                                    for (int c1 = 0; c1 < 2; c1++) {
                                        if (a != r2 * 2 + r1 || b != c2 * 2 + c1) {
                                            continue;
                                        }
                                        C left = PAULI[i][r2][c2];
                                        C right = PAULI[j][r1][c1].conj();
                                        sum = sum.add(left.mul(right).mul(new C(m[i][j], 0)));
                                    }
                                }
                            }
                        }
                    }
                }
                sum = sum.mul(new C(0.5, 0));
                reOut[a][b] = sum.re();
                imOut[a][b] = sum.im();
            }
        }
    }

    /** Eigenvalues of the Hermitian coherency matrix via an 8x8 real symmetric embedding. */
    public static double[] coherencyEigenvalues(double[][] m) {
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        coherencyMatrix(m, re, im);
        double[][] big = new double[8][8];
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                big[a][b] = re[a][b];
                big[a][b + 4] = -im[a][b];
                big[a + 4][b] = im[a][b];
                big[a + 4][b + 4] = re[a][b];
            }
        }
        double[] all = new EigenDecomposition(new Array2DRowRealMatrix(big)).getRealEigenvalues();
        java.util.Arrays.sort(all);
        double[] unique = new double[4];
        for (int i = 0; i < 4; i++) {
            unique[i] = all[2 * i];
        }
        return unique;
    }

    public record Realizability(boolean physical, double minEigenvalue, double[] eigenvalues, double residual) {
    }

    /** PSD test; also reports passivity m00 in (0, 1] after the caller controls normalization. */
    public static Realizability checkRealizability(double[][] m) {
        double[] eig = coherencyEigenvalues(m);
        double min = eig[0];
        double norm = Math.max(1.0, DMatrix.frobenius(MatrixUtils.createRealMatrix(m)));
        boolean physical = min >= -REALIZABILITY_TOLERANCE * norm;
        return new Realizability(physical, min, eig, physical ? 0.0 : -min / norm);
    }

    /** Depolarization index DI = sqrt(sum m_ij^2 - m00^2) / (sqrt(3) m00). */
    public static double depolarizationIndex(double[][] m) {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                sum += m[i][j] * m[i][j];
            }
        }
        if (Math.abs(m[0][0]) < 1e-300) {
            return Double.NaN;
        }
        return Math.sqrt(Math.max(0, sum - m[0][0] * m[0][0])) / (Math.sqrt(3) * m[0][0]);
    }

    public record LuChipmanResult(boolean available, double diattenuation, double depolarization,
                                  double retardanceDegrees, double opticalRotationDegrees,
                                  double[][] mDelta, double[][] mR, double[][] mD, String note) {
    }

    /**
     * Lu-Chipman forward decomposition M = M_delta M_R M_D.
     * Attempted for every candidate; when the matrix is non-physical or
     * degenerate the result is flagged (never silently replaced by a fit).
     */
    public static LuChipmanResult luChipman(double[][] m) {
        double m00 = m[0][0];
        if (!(Math.abs(m00) > 1e-12)) {
            return new LuChipmanResult(false, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    null, null, null, "m00=0: Lu-Chipman decomposition undefined");
        }
        double[][] mdBlock = new double[3][3];
        double[] d = new double[] {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
        double dNorm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double diattenuation = dNorm;
        double a = Math.sqrt(Math.max(0, 1 - dNorm * dNorm));
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mdBlock[i][j] = (i == j ? a : 0) + (1 - a) * (dNorm > 1e-12 ? d[i] * d[j] / Math.max(dNorm * dNorm, 1e-300) : 0);
            }
        }
        double[][] md = new double[4][4];
        md[0][0] = 1;
        for (int i = 0; i < 3; i++) {
            md[0][i + 1] = d[i];
            md[i + 1][0] = d[i];
            for (int j = 0; j < 3; j++) {
                md[i + 1][j + 1] = mdBlock[i][j];
            }
        }
        RealMatrix mdMatrix = new Array2DRowRealMatrix(md);
        RealMatrix mdInv;
        try {
            mdInv = DMatrix.inverseVerified(mdMatrix);
        } catch (ArithmeticException e) {
            return new LuChipmanResult(false, diattenuation, Double.NaN, Double.NaN, Double.NaN,
                    null, null, md, "diattenuator block singular: decomposition refused");
        }
        RealMatrix mPrime = new Array2DRowRealMatrix(m).multiply(mdInv);
        double[][] mp = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mp[i][j] = mPrime.getEntry(i + 1, j + 1);
            }
        }
        SingularValueDecomposition svd = new SingularValueDecomposition(new Array2DRowRealMatrix(mp));
        RealMatrix u = svd.getU();
        RealMatrix v = svd.getVT().transpose();
        RealMatrix mRot = u.multiply(v.transpose());
        RealMatrix mDep = u.multiply(svd.getS()).multiply(u.transpose());
        String note = null;
        double det = determinant3(mRot.getData());
        if (det < 0) {
            note = "m_R has det=-1 (improper rotation): reported but not a physical diattenuator-free retarder";
        }
        double trace = mRot.getTrace();
        double cosR = Math.max(-1, Math.min(1, (trace - 1) / 2));
        double retardance = Math.toDegrees(Math.acos(cosR));
        double opticalRotation = Math.toDegrees(0.5 * Math.asin(Math.max(-1, Math.min(1,
                (mRot.getEntry(0, 1) - mRot.getEntry(1, 0)) / 2))));
        double depolarization = 1 - Math.max(0, Math.min(1.5, mDep.getTrace() / 3));
        double[][] mDeltaFull = new double[4][4];
        mDeltaFull[0][0] = 1;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mDeltaFull[i + 1][j + 1] = mDep.getEntry(i, j);
            }
        }
        double[][] mrFull = new double[4][4];
        mrFull[0][0] = 1;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mrFull[i + 1][j + 1] = mRot.getEntry(i, j);
            }
        }
        return new LuChipmanResult(true, diattenuation, depolarization, retardance, opticalRotation,
                mDeltaFull, mrFull, md, note);
    }

    private static double determinant3(double[][] a) {
        return a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    }

    /** Cloude entropy -sum p_i ln p_i / ln 4 from coherency eigenvalues; NaN when undefined. */
    public static double cloudeEntropy(double[][] m) {
        double[] eig = coherencyEigenvalues(m);
        double trace = 0;
        for (double e : eig) {
            if (e < 0) {
                return Double.NaN;
            }
            trace += e;
        }
        if (trace <= 1e-300) {
            return Double.NaN;
        }
        double h = 0;
        for (double e : eig) {
            double p = e / trace;
            if (p > 1e-15) {
                h -= p * Math.log(p);
            }
        }
        return h / Math.log(4);
    }
}
