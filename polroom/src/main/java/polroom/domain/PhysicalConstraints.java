package polroom.domain;

import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical-realizability checks for Mueller matrices (canonical SHV order).
 *
 * <p>A Mueller matrix M is realizable when its 4x4 Cloude coherency matrix T
 * is positive-semidefinite (Cloude 1996). T is Hermitian with real and
 * imaginary parts built from the Mueller elements using the standard mapping:
 *
 * <pre>
 * T = 1/4
 * | m00+m01+m10+m11   m02+im03+m12+im13   m20-im23+m30+im31   m22+m33+i(m32-m23) |
 * | m02-im03+m12-im13 m00-m01+m10-m11     m22-m33-i(m23+m32)  m20+m30-i(m21+m31) |
 * | m20+im23+m30-im31 m22-m33+i(m23+m32)  m00+m01-m10-m11     m02-m03+i(m12-m13) |
 * | m22+m33-i(m32-m23) m20+m30+i(m21+m31) m02+m03-i(m12+m13)  m00-m01-m10+m11    |
 * </pre>
 */
public final class PhysicalConstraints {

    public record Violation(String rule, double value, double bound, String detail) {
    }

    public static class Report {
        public final double[][] tReal;
        public final double[][] tImag;
        public final double[] eigenvalues;
        public final double minEigenvalue;
        public final double depolarizationIndex;
        public final double cloudeEntropy;
        public final double passivityMax;
        public final List<Violation> violations = new ArrayList<>();
        public final boolean physicallyRealizable;

        Report(double[][] tReal, double[][] tImag, double[] eigenvalues, double minEigenvalue,
               double depolarizationIndex, double cloudeEntropy, double passivityMax,
               List<Violation> violations) {
            this.tReal = tReal;
            this.tImag = tImag;
            this.eigenvalues = eigenvalues;
            this.minEigenvalue = minEigenvalue;
            this.depolarizationIndex = depolarizationIndex;
            this.cloudeEntropy = cloudeEntropy;
            this.passivityMax = passivityMax;
            this.violations.addAll(violations);
            this.physicallyRealizable = violations.isEmpty();
        }
    }

    /** Returns {real, imag} parts of the Cloude coherency matrix. */
    public static double[][][] coherency(RealMatrix m) {
        double[][] a = m.getData();
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];

        re[0][0] = a[0][0] + a[0][1] + a[1][0] + a[1][1];
        re[0][1] = a[0][2] + a[1][2];
        im[0][1] = a[0][3] + a[1][3];
        re[0][2] = a[2][0] + a[3][0];
        im[0][2] = -a[2][3] + a[3][1];
        re[0][3] = a[2][2] + a[3][3];
        im[0][3] = a[3][2] - a[2][3];

        re[1][1] = a[0][0] - a[0][1] + a[1][0] - a[1][1];
        re[1][2] = a[2][2] - a[3][3];
        im[1][2] = -(a[2][3] + a[3][2]);
        re[1][3] = a[2][0] + a[3][0];
        im[1][3] = -(a[2][1] + a[3][1]);

        re[2][2] = a[0][0] + a[0][1] - a[1][0] - a[1][1];
        re[2][3] = a[0][2] - a[1][2];
        im[2][3] = -a[0][3] + a[1][3];

        re[3][3] = a[0][0] - a[0][1] - a[1][0] + a[1][1];

        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                re[j][i] = re[i][j];
                im[j][i] = -im[i][j];
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                re[i][j] *= 0.25;
                im[i][j] *= 0.25;
            }
        }
        return new double[][][]{re, im};
    }

    public static RealMatrix coherencyReal(RealMatrix m) {
        return Mat.m(coherency(m)[0]);
    }

    public static Report check(RealMatrix m) {
        double[][][] t = coherency(m);
        double[] eig = Mat.hermitianEigenvalues(t[0], t[1]);
        double minEig = eig[0];
        for (double v : eig) {
            minEig = Math.min(minEig, v);
        }
        List<Violation> violations = new ArrayList<>();
        double eigTol = -1e-8 * Math.max(1.0, Math.abs(m.getEntry(0, 0)));
        if (minEig < eigTol) {
            violations.add(new Violation("cloude_psd", minEig, 0.0,
                    "Cloude coherency matrix has a negative eigenvalue"));
        }
        double m00 = m.getEntry(0, 0);
        double passivity = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                passivity = Math.max(passivity, Math.abs(m.getEntry(i, j)));
            }
        }
        if (m00 < -1e-10) {
            violations.add(new Violation("positive_throughput", m00, 0.0,
                    "m00 must be non-negative"));
        }
        if (passivity > Math.abs(m00) * (1 + 1e-8) + 1e-10) {
            violations.add(new Violation("passivity_bound", passivity, Math.abs(m00),
                    "|m_ij| <= m00 violated"));
        }
        double sumSq = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                sumSq += m.getEntry(i, j) * m.getEntry(i, j);
            }
        }
        double di = m00 == 0 ? 0 : Math.sqrt(sumSq / (3.0 * m00 * m00));
        double trace = Math.max(1e-300, traceReal(t[0]));
        double entropy = 0;
        for (double v : eig) {
            if (v > 1e-14) {
                double p = v / trace;
                if (p > 0) {
                    entropy -= p * (Math.log(p) / Math.log(4));
                }
            }
        }
        return new Report(t[0], t[1], eig, minEig, di, entropy, passivity, violations);
    }

    private static double traceReal(double[][] re) {
        double s = 0;
        for (int i = 0; i < re.length; i++) {
            s += re[i][i];
        }
        return s;
    }

    private PhysicalConstraints() {
    }
}
