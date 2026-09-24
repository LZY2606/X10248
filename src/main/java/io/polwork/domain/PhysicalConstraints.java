package io.polwork.domain;

import io.polwork.linalg.Complex;
import io.polwork.linalg.Matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Cloude coherency-matrix test for physical realizability.
 *
 * <p>The 4x4 Mueller matrix M is mapped with the Pauli basis to the 4x4 Hermitian
 * coherency matrix T using the standard closed-form Cloude relations. A physically
 * realizable Mueller matrix has T positive semidefinite, i.e. all four eigenvalues
 * non-negative. Non-physical candidates are flagged with their violations and never
 * projected before being presented as the original candidate.
 */
public final class PhysicalConstraints {

    private PhysicalConstraints() {
    }

    public record CoherencyResult(Complex[][] t, double[] eigenvalues, double minEigenvalue) {
    }

    public record Violation(String code, String message, double value, double limit) {
    }

    public static class PhysicalReport {
        public CoherencyResult coherency;
        public double m00;
        public double diattenuationNorm;
        public double polarizanceNorm;
        public final List<Violation> violations = new ArrayList<>();
        public boolean realizable;
    }

    /** Standard Cloude coherency matrix from a Mueller matrix in (S0,S1,S2,S3) order. */
    public static Complex[][] coherencyMatrix(double[][] m) {
        double m00 = m[0][0], m01 = m[0][1], m02 = m[0][2], m03 = m[0][3];
        double m10 = m[1][0], m11 = m[1][1], m12 = m[1][2], m13 = m[1][3];
        double m20 = m[2][0], m21 = m[2][1], m22 = m[2][2], m23 = m[2][3];
        double m30 = m[3][0], m31 = m[3][1], m32 = m[3][2], m33 = m[3][3];

        Complex[][] t = new Complex[4][4];
        t[0][0] = Complex.of(m00 + m11 + m22 + m33, 0);
        t[0][1] = Complex.of(m01 + m10, -(m23 - m32));
        t[0][2] = Complex.of(m02 + m20, m13 - m31);
        t[0][3] = Complex.of(m03 + m30, -(m12 - m21));

        t[1][0] = t[0][1].conj();
        t[1][1] = Complex.of(m00 + m11 - m22 - m33, 0);
        t[1][2] = Complex.of(m12 + m21, -(m03 - m30));
        t[1][3] = Complex.of(m13 + m31, m02 - m20);

        t[2][0] = t[0][2].conj();
        t[2][1] = t[1][2].conj();
        t[2][2] = Complex.of(m00 - m11 + m22 - m33, 0);
        t[2][3] = Complex.of(m23 + m32, -(m01 - m10));

        t[3][0] = t[0][3].conj();
        t[3][1] = t[1][3].conj();
        t[3][2] = t[2][3].conj();
        t[3][3] = Complex.of(m00 - m11 - m22 + m33, 0);

        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                t[i][j] = t[i][j].scale(0.25);
            }
        }
        return t;
    }

    /** Eigenvalues of a 4x4 Hermitian matrix via the real 8x8 block embedding. */
    static double[] eigenvaluesHermitian(Complex[][] t) {
        int n = t.length;
        double[][] block = new double[2 * n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double re = t[i][j].re;
                double im = t[i][j].im;
                block[i][j] = re;
                block[i + n][j + n] = re;
                block[i][j + n] = -im;
                block[i + n][j] = im;
            }
        }
        Matrix.JacobiResult e = Matrix.jacobiSymmetric(Matrix.of(block));
        double[] values = new double[n];
        System.arraycopy(e.values, 0, values, 0, n);
        return values;
    }

    public static PhysicalReport evaluate(double[][] m) {
        PhysicalReport report = new PhysicalReport();
        report.m00 = m[0][0];
        double scale = Math.max(1.0, Math.abs(m[0][0]));

        Complex[][] t = coherencyMatrix(m);
        double[] eig = eigenvaluesHermitian(t);
        report.coherency = new CoherencyResult(t, eig, eig[3]);

        double d1 = m[0][1] / scale;
        double d2 = m[0][2] / scale;
        double d3 = m[0][3] / scale;
        report.diattenuationNorm = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
        double p1 = m[1][0] / scale;
        double p2 = m[2][0] / scale;
        double p3 = m[3][0] / scale;
        report.polarizanceNorm = Math.sqrt(p1 * p1 + p2 * p2 + p3 * p3);

        double eigTol = -1e-9 * scale;
        for (int k = 0; k < 4; k++) {
            if (eig[k] < eigTol) {
                report.violations.add(new Violation("NEGATIVE_COHERENCY_EIGENVALUE",
                        "Cloude coherency eigenvalue lambda_" + k + " = "
                                + String.format("%.6g", eig[k])
                                + " violates positive semidefiniteness (T not PSD)", eig[k], 0.0));
            }
        }
        if (m[0][0] < -1e-12 * scale) {
            report.violations.add(new Violation("NEGATIVE_M00",
                    "m00 = " + m[0][0] + " is negative; transmittance cannot be negative",
                    m[0][0], 0.0));
        }
        if (report.diattenuationNorm > 1.0 + 1e-9) {
            report.violations.add(new Violation("DIATTENUATION_UNBOUND",
                    "diattenuation vector norm " + report.diattenuationNorm + " exceeds 1",
                    report.diattenuationNorm, 1.0));
        }
        if (report.polarizanceNorm > 1.0 + 1e-9) {
            report.violations.add(new Violation("POLARIZANCE_UNBOUND",
                    "polarizance vector norm " + report.polarizanceNorm + " exceeds 1",
                    report.polarizanceNorm, 1.0));
        }
        report.realizable = report.violations.isEmpty();
        return report;
    }

    public List<Violation> violations() {
        return new ArrayList<>();
    }
}
