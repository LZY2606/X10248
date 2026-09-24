package pol.mat;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller matrix decomposition candidates.
 *
 * <p>Two candidate families are always produced (even for non-physical inputs, in which
 * case the candidate itself is flagged and complex/sqrt pathologies are reported):
 * <ul>
 *   <li>{@code LU-CHIPMAN} — polar decomposition M = M_Delta M_R M_D (Lu &amp; Chipman 1996).</li>
 *   <li>{@code CLOUDE-SUM} — spectral sum of the Cloude coherency matrix.</li>
 * </ul>
 */
public final class Decomposition {
    private Decomposition() {}

    public static final class Factor {
        public final String factorId;
        public final RealMatrix matrix;
        public final double parameter;

        public Factor(String factorId, RealMatrix matrix, double parameter) {
            this.factorId = factorId;
            this.matrix = matrix;
            this.parameter = parameter;
        }
    }

    public static final class Candidate {
        public final String method;
        public final String variant;
        public final List<Factor> factors;
        public final List<String> diagnostics;

        Candidate(String method, String variant, List<Factor> factors, List<String> diagnostics) {
            this.method = method;
            this.variant = variant;
            this.factors = factors;
            this.diagnostics = diagnostics;
        }
    }

    public static List<Candidate> luChipman(RealMatrix mIn) {
        List<String> diag = new ArrayList<>();
        RealMatrix m = mIn.copy();
        double m00 = m.getEntry(0, 0);
        if (!(m00 > 1e-12)) {
            diag.add("M00 non-positive; Lu-Chipman normalisation is undefined");
            return List.of(new Candidate("LU-CHIPMAN", "STANDARD", List.of(), diag));
        }
        double[] d = new double[3];
        for (int k = 0; k < 3; k++) {
            d[k] = m.getEntry(0, k + 1) / m00;
        }
        double d2 = M.dot(d, d);
        if (d2 >= 1.0 - 1e-10) {
            diag.add("Diattenuation magnitude reaches 1; ideal-diattenuator limit is singular");
            return List.of(new Candidate("LU-CHIPMAN", "STANDARD", List.of(), diag));
        }
        double dmag = Math.sqrt(d2);

        // M_D
        double dd = Math.sqrt(1 - d2);
        RealMatrix md = MatrixUtils.createRealIdentityMatrix(4);
        md.setEntry(3, 3, dd);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double v = (1 - dd) * (d[i] * d[j]) / (d2 == 0 ? 1 : d2);
                md.setEntry(i, j, md.getEntry(i, j) + v);
            }
        }

        // polarizance row of M Delta
        double[] p = new double[3];
        for (int k = 0; k < 3; k++) {
            p[k] = m.getEntry(k + 1, 0) / m00;
        }
        double[] pd = new double[3];
        double q = Math.sqrt(1 - d2);
        for (int k = 0; k < 3; k++) {
            pd[k] = (p[k] - d[k] * dot(p, d)) / q;
        }

        // normalised lower 3x3 blocks
        double[][] mm = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mm[i][j] = m.getEntry(i + 1, j + 1) / m00;
            }
        }
        double[][] mmTmm = mulT(mm, mm);
        double[][] pp = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                pp[i][j] = pd[i] * pd[j];
            }
        }
        double[][] inside = sub(mmTmm, pp);
        double[] ev = new double[1];
        double[][] mdelta3 = sqrtSymmetric(inside, ev, diag);
        double mdeltaMean = ev[0];
        if (mdeltaMean <= 1e-12) {
            diag.add("Delta depolariser mean eigenvalue non-positive; decomposition unreliable");
            return List.of(new Candidate("LU-CHIPMAN", "STANDARD", List.of(), diag));
        }

        double[][] mr3 = mul(inverse3(mdelta3), mm);

        RealMatrix mDelta = MatrixUtils.createRealIdentityMatrix(4);
        for (int i = 0; i < 3; i++) {
            mDelta.setEntry(i + 1, 0, pd[i]);
            for (int j = 0; j < 3; j++) {
                mDelta.setEntry(i + 1, j + 1, mdelta3[i][j]);
            }
        }
        RealMatrix mR = MatrixUtils.createRealIdentityMatrix(4);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mR.setEntry(i + 1, j + 1, mr3[i][j]);
            }
        }

        List<Factor> factors = new ArrayList<>();
        factors.add(new Factor("M_D", md, dmag));
        factors.add(new Factor("M_R", mR, retardance(mr3, diag)));
        factors.add(new Factor("M_Delta", mDelta, depolarisationIndex(mdelta3, mdeltaMean)));
        // Reconstruction M00 * M_Delta M_R M_D is the verification object; expose residual.
        RealMatrix rec = mDelta.multiply(mR).multiply(md).scalarMultiply(m00);
        double res = rec.subtract(mIn).getFrobeniusNorm() / Math.max(1, mIn.getFrobeniusNorm());
        if (res > 1e-7) {
            diag.add("Reconstruction residual " + res + " above 1e-7");
        }
        return List.of(new Candidate("LU-CHIPMAN", "STANDARD", factors, diag));
    }

    private static double retardance(double[][] r, List<String> diag) {
        double tr = r[0][0] + r[1][1] + r[2][2];
        double c = (tr - 1) / 2.0;
        if (c < -1) {
            c = -1;
            diag.add("Retardance cosine clipped from below -1");
        }
        if (c > 1) {
            c = 1;
            diag.add("Retardance cosine clipped above 1");
        }
        return Math.acos(c);
    }

    private static double depolarisationIndex(double[][] mdelta3, double mean) {
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                sum += mdelta3[i][j] * mdelta3[i][j];
            }
        }
        return 1.0 - Math.sqrt(sum / 3.0);
    }

    public static List<Candidate> cloude(RealMatrix m) {
        List<String> diag = new ArrayList<>();
        CM h = Coherency.fromMueller(m);
        CM.Eigh eigh = CM.eighHermitian(h);
        double sum = 0;
        for (double v : eigh.values) {
            sum += v;
        }
        List<Factor> factors = new ArrayList<>();
        int nonZero = 0;
        for (int k = 0; k < 4; k++) {
            double lambda = eigh.values[k];
            if (Math.abs(lambda) < 1e-12) {
                continue;
            }
            nonZero++;
            if (lambda < -1e-9) {
                diag.add("Eigencomponent #" + k + " has negative eigenvalue " + lambda
                        + "; kept, not projected away");
            }
            double w = sum != 0 ? lambda / sum : 0;
            CM hk = new CM(4);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    double re = eigh.vectors.re[i][k] * eigh.vectors.re[j][k]
                            + eigh.vectors.im[i][k] * eigh.vectors.im[j][k];
                    double im = eigh.vectors.im[i][k] * eigh.vectors.re[j][k]
                            - eigh.vectors.re[i][k] * eigh.vectors.im[j][k];
                    hk.re[i][j] = lambda * re;
                    hk.im[i][j] = lambda * im;
                }
            }
            RealMatrix mk = Coherency.toMueller(hk);
            factors.add(new Factor("Cloude-" + k, mk, w));
        }
        if (nonZero > 1) {
            diag.add("Depolarising: " + nonZero + " non-zero coherency components");
        }
        return List.of(new Candidate("CLOUDE-SUM", "SPECTRAL", factors, diag));
    }

    private static double dot(double[] a, double[] b) {
        return M.dot(a, b);
    }

    private static double[][] mulT(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    c[i][j] += a[k][i] * b[k][j];
                }
            }
        }
        return c;
    }

    private static double[][] sub(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                c[i][j] = a[i][j] - b[i][j];
            }
        }
        return c;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    c[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return c;
    }

    private static double[][] inverse3(double[][] a) {
        return new LUDecomposition(new Array2DRowRealMatrix(a, false))
                .getSolver().getInverse().getData();
    }

    private static double[][] sqrtSymmetric(double[][] s, double[] meanOut, List<String> diag) {
        org.apache.commons.math3.linear.EigenDecomposition eig =
                new org.apache.commons.math3.linear.EigenDecomposition(
                        MatrixUtils.createRealMatrix(s));
        double mean = (eig.getRealEigenvalues()[0]
                + eig.getRealEigenvalues()[1]
                + eig.getRealEigenvalues()[2]) / 3.0;
        meanOut[0] = mean;
        RealMatrix sqrt = MatrixUtils.createRealMatrix(3, 3);
        for (int k = 0; k < 3; k++) {
            double lambda = eig.getRealEigenvalues()[k];
            if (lambda < -1e-9) {
                diag.add("Negative sqrt radicand " + lambda + " for depolariser block");
            }
            double root = lambda >= 0 ? Math.sqrt(lambda) : 0;
            double[] v = eig.getEigenvector(k).toArray();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    sqrt.addToEntry(i, j, root * v[i] * v[j]);
                }
            }
        }
        return sqrt.getData();
    }
}
