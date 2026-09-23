package com.polarlab.optics;

import com.polarlab.math.CMat;
import com.polarlab.math.Cmplx;
import com.polarlab.math.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cloude coherency-matrix test for physical realizability of a Mueller matrix.
 *
 * H = (1/4) sum_{i,j=0..3} m_{ij} (sigma_i (x) sigma_j^*) with the Pauli basis
 * sigma_0=I, sigma_1=diag(1,-1), sigma_2=[[0,-i],[i,0]], sigma_3=[[0,1],[1,0]].
 * A Mueller matrix is physically realizable iff H is Hermitian positive semi-definite.
 *
 * Non-physical candidates keep their original values and are reported with violations;
 * the spectral-clip repair is exposed only as a separately labeled candidate.
 */
public final class PhysicalRealizability {

    public static final class Violation {
        public final String kind;
        public final double value;
        public final String detail;

        Violation(String kind, double value, String detail) {
            this.kind = kind;
            this.value = value;
            this.detail = detail;
        }
    }

    public static final class Report {
        public final boolean physical;
        public final double[] coherencyEigenvalues;
        public final List<Violation> violations;
        public final double maxTransmittance;
        public final double depolarizationIndex;

        Report(boolean physical, double[] eig, List<Violation> v, double tmax, double pIndex) {
            this.physical = physical;
            this.coherencyEigenvalues = eig;
            this.violations = v;
            this.maxTransmittance = tmax;
            this.depolarizationIndex = pIndex;
        }
    }

    // Pauli matrices encoded as complex 2x2: p[k][row][col]
    private static final Cmplx[][][] P = new Cmplx[4][2][2];

    static {
        for (int k = 0; k < 4; k++)
            for (int r = 0; r < 2; r++)
                for (int c = 0; c < 2; c++) P[k][r][c] = Cmplx.ZERO;
        P[0][0][0] = Cmplx.ONE;
        P[0][1][1] = Cmplx.ONE;
        P[1][0][0] = Cmplx.ONE;
        P[1][1][1] = Cmplx.of(-1);
        P[2][0][1] = new Cmplx(0, -1);
        P[2][1][0] = Cmplx.I;
        P[3][0][1] = Cmplx.ONE;
        P[3][1][0] = Cmplx.ONE;
    }

    /** Element (R,C) of sigma_i (x) sigma_j^* for 4x4 indices 0..3. */
    private static Cmplx basisElement(int i, int j, int r, int c) {
        int ir = r / 2, ic = c / 2, jr = r % 2, jc = c % 2;
        return P[i][ir][ic].mul(P[j][jr][jc].conj());
    }

    /** Build the 4x4 coherency matrix for Mueller matrix m. */
    public static CMat coherency(Mat m) {
        CMat h = new CMat(4);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                Cmplx v = Cmplx.ZERO;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        v = v.add(basisElement(i, j, r, c).mul(0.25 * m.a[i][j]));
                    }
                }
                h.set(r, c, v);
            }
        }
        return h;
    }

    /** Inverse map: m_{ij} = tr[ H (sigma_j (x) sigma_i^*) ] (orthogonal basis, trace delta). */
    public static Mat fromCoherency(CMat h) {
        Mat m = new Mat(4, 4);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Cmplx trace = Cmplx.ZERO;
                for (int r = 0; r < 4; r++) {
                    trace = trace.add(h.get(r, r).mul(basisElement(j, i, r, r)));
                }
                m.a[i][j] = trace.re;
            }
        }
        return m;
    }

    public static Report check(Mat m) {
        CMat h = coherency(m);
        CMat.Eigh e = h.eigh();
        List<Violation> violations = new ArrayList<>();
        double minEig = e.eig[3];
        if (minEig < -1e-8) {
            violations.add(new Violation("coherency-negative-eigenvalue", minEig,
                    String.format(Locale.ROOT,
                            "Cloude coherency eigenvalue %.6g < 0: Mueller matrix not physically realizable",
                            minEig)));
        }
        double m00 = m.a[0][0];
        if (m00 < -1e-12) {
            violations.add(new Violation("negative-m00", m00,
                    "m00 < 0: negative average intensity transmittance"));
        }
        double tmax = m00 + Math.sqrt(m.a[0][1] * m.a[0][1]
                + m.a[0][2] * m.a[0][2] + m.a[0][3] * m.a[0][3]);
        if (tmax > 1.0 + 1e-8) {
            violations.add(new Violation("max-transmittance>1", tmax,
                    String.format(Locale.ROOT,
                            "maximum intensity transmittance %.6g exceeds 1", tmax)));
        }
        double pNum = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) pNum += m.a[i][j] * m.a[i][j];
        double pIndex = Math.abs(m00) > 1e-12 ? Math.sqrt(pNum) / Math.abs(m00)
                : Double.POSITIVE_INFINITY;
        if (pIndex > Math.sqrt(3) + 1e-8) {
            violations.add(new Violation("depolarization-index", pIndex,
                    String.format(Locale.ROOT, "depolarization index %.6g > sqrt(3)", pIndex)));
        }
        return new Report(violations.isEmpty(), e.eig, violations, tmax, pIndex);
    }

    /**
     * Nearest physical candidate by clipping negative coherency eigenvalues. Returned as a
     * separate labeled candidate; the original candidate is never overwritten.
     */
    public static Mat nearestPhysical(Mat m) {
        return fromCoherency(coherency(m).clipNegativeEigenvalues());
    }
}
