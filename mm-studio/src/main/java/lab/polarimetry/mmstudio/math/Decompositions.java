package lab.polarimetry.mmstudio.math;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiple Mueller decomposition candidates. Every candidate is kept; none is
 * silently promoted to "the" interpretation before human verdict.
 */
public final class Decompositions {
    private Decompositions() {}

    public record Factor(String role, RealMatrix matrix) {}

    public record Candidate(String id, String name, String convention,
                            String formula, List<Factor> factors,
                            double depolarizationIndex, double diattenuation,
                            double retardanceRad, String notes) {}

    public static List<Candidate> all(RealMatrix m) {
        List<Candidate> out = new ArrayList<>();
        out.add(polarForward(m));
        out.add(polarReverse(m));
        out.add(cloudeSpectral(m));
        return out;
    }

    private static double depolarizationIndex(RealMatrix m) {
        double s = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (i != 0 || j != 0) s += m.getEntry(i, j) * m.getEntry(i, j);
        return Math.sqrt(s) / Math.abs(m.getEntry(0, 0));
    }

    private static RealMatrix diattenuator(double m00, double[] dRow, double[] dCol) {
        // D = [[m00, d^T],[d, sqrt(m00^2 I - d d^T)]] with d = first column (generator-side)
        RealMatrix d = new Array2DRowRealMatrix(3, 1);
        for (int i = 0; i < 3; i++) d.setEntry(i, 0, dCol[i]);
        RealMatrix inner = Mx.identity(3).scalarMultiply(m00 * m00)
                .subtract(d.multiply(d.transpose()));
        RealMatrix sq = Mx.sqrtPsd(inner);
        RealMatrix out = Mx.identity(4).scalarMultiply(0);
        out.setEntry(0, 0, m00);
        for (int i = 0; i < 3; i++) {
            out.setEntry(0, i + 1, dRow[i]);
            out.setEntry(i + 1, 0, dCol[i]);
            for (int j = 0; j < 3; j++) out.setEntry(i + 1, j + 1, sq.getEntry(i, j));
        }
        return out;
    }

    /** Lu-Chipman style forward polar decomposition: M = mDelta * mR * mD. */
    static Candidate polarForward(RealMatrix m) {
        double m00 = m.getEntry(0, 0);
        double[] dRow = {m.getEntry(0, 1), m.getEntry(0, 2), m.getEntry(0, 3)};
        double[] dCol = {m.getEntry(1, 0), m.getEntry(2, 0), m.getEntry(3, 0)};
        RealMatrix mD = diattenuator(m00, dRow, dCol);
        RealMatrix mDinv;
        try {
            mDinv = Mx.inverse(mD);
        } catch (SingularMatrixException ex) {
            return new Candidate("lu_chipman_forward", "Lu-Chipman forward M=Delta R D",
                    "right-to-left forward polar order (Chipman 1996)",
                    "M = mDelta*mR*mD", List.of(),
                    depolarizationIndex(m), norm(dRow), Double.NaN,
                    "diattenuator singular; forward order not numerically defined");
        }
        RealMatrix residual = m.multiply(mDinv);
        RealMatrix prSub = sub3(residual);
        RealMatrix prSqrt = Mx.sqrtPsd(Mx.symm(prSub));
        RealMatrix mR = Mx.identity(4);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) mR.setEntry(i + 1, j + 1, prSqrt.getEntry(i, j));
        RealMatrix mDelta = residual.multiply(Mx.inverse(mR));
        double ret = retardanceFrom3(sub3(mR));
        List<Factor> f = List.of(new Factor("mDelta (depolarizer)", mDelta),
                new Factor("mR (retarder)", mR), new Factor("mD (diattenuator)", mD));
        return new Candidate("lu_chipman_forward", "Lu-Chipman forward M=Delta R D",
                "right-to-left forward polar order (Chipman 1996)",
                "M = mDelta*mR*mD", f, depolarizationIndex(m), norm(dRow), ret,
                "forward order; reconstruction rms=" + rms(m, mDelta.multiply(mR).multiply(mD)));
    }

    /** Reverse polar decomposition: M = mD * mR * mDelta. */
    static Candidate polarReverse(RealMatrix m) {
        double m00 = m.getEntry(0, 0);
        double[] dRow = {m.getEntry(0, 1), m.getEntry(0, 2), m.getEntry(0, 3)};
        double[] dCol = {m.getEntry(1, 0), m.getEntry(2, 0), m.getEntry(3, 0)};
        RealMatrix mD = diattenuator(m00, dRow, dCol);
        RealMatrix mDinv;
        try {
            mDinv = Mx.inverse(mD);
        } catch (SingularMatrixException ex) {
            return new Candidate("lu_chipman_reverse", "Lu-Chipman reverse M=D R Delta",
                    "left-to-right reverse polar order",
                    "M = mD*mR*mDelta", List.of(),
                    depolarizationIndex(m), norm(dRow), Double.NaN,
                    "diattenuator singular; reverse order not numerically defined");
        }
        RealMatrix residual = mDinv.multiply(m);
        RealMatrix prSub = sub3(residual);
        RealMatrix prSqrt = Mx.sqrtPsd(Mx.symm(prSub));
        RealMatrix mR = Mx.identity(4);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) mR.setEntry(i + 1, j + 1, prSqrt.getEntry(i, j));
        RealMatrix mDelta = Mx.inverse(mR).multiply(residual);
        double ret = retardanceFrom3(sub3(mR));
        List<Factor> f = List.of(new Factor("mD (diattenuator)", mD),
                new Factor("mR (retarder)", mR), new Factor("mDelta (depolarizer)", mDelta));
        return new Candidate("lu_chipman_reverse", "Lu-Chipman reverse M=D R Delta",
                "left-to-right reverse polar order",
                "M = mD*mR*mDelta", f, depolarizationIndex(m), norm(dRow), ret,
                "reverse order; reconstruction rms=" + rms(m, mD.multiply(mR).multiply(mDelta)));
    }

    /** Cloude spectral decomposition: M = sum lambda_k M_Jk into pure (non-depolarizing) components. */
    static Candidate cloudeSpectral(RealMatrix m) {
        RealMatrix r = Pauli.coherencyReal(m);
        EigenDecomposition e = new EigenDecomposition(r);
        List<Factor> factors = new ArrayList<>();
        double diatt = Math.sqrt(m.getEntry(0,1)*m.getEntry(0,1)
                + m.getEntry(0,2)*m.getEntry(0,2) + m.getEntry(0,3)*m.getEntry(0,3)) / Math.abs(m00Safe(m));
        double[] used = new double[4];
        int ui = 0;
        for (int k = 7; k >= 0; k--) {
            double v = e.getRealEigenvalue(k);
            boolean dup = false;
            for (int t = 0; t < ui; t++) if (Math.abs(used[t] - v) < 1e-8) dup = true;
            if (dup) continue;
            used[ui++] = v;
            RealMatrix vec = e.getEigenvector(k);
            Complex[] a = new Complex[4];
            for (int i = 0; i < 4; i++) {
                a[i] = new Complex(vec.getEntry(i, 0), vec.getEntry(i + 4, 0));
            }
            RealMatrix mj = Pauli.pureMueller(a);
            factors.add(new Factor(String.format("M_J (lambda=%.6g)", v), mj));
            if (factors.size() == 4) break;
        }
        String neg = java.util.Arrays.stream(Pauli.coherencyEigenvalues(m))
                .anyMatch(x -> x < -1e-7)
                ? "negative eigenvalue present: physical-realizability violation flagged"
                : "all kept eigenvalues non-negative";
        return new Candidate("cloude_spectral", "Cloude spectral (sum of pure Mueller matrices)",
                "coherency R = 1/8 sum M_ab Tr(si sa sj sb); eigenvectors mapped back",
                "M = sum_k lambda_k M_Jk", factors, depolarizationIndex(m), diatt, Double.NaN,
                neg + "; negative-eigenvalue components are retained for inspection, never dropped");
    }

    private static double m00Safe(RealMatrix m) {
        double v = m.getEntry(0, 0);
        return v == 0 ? 1e-300 : v;
    }

    private static double retardanceFrom3(RealMatrix sub3) {
        double tr = sub3.getTrace();
        double c = (tr - 1.0) / 2.0;
        c = Math.max(-1, Math.min(1, c));
        return Math.acos(c);
    }

    private static RealMatrix sub3(RealMatrix m) {
        RealMatrix out = Mx.zeros(3, 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) out.setEntry(i, j, m.getEntry(i + 1, j + 1));
        return out;
    }

    private static double norm(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        return Math.sqrt(s);
    }

    private static double rms(RealMatrix a, RealMatrix b) {
        double s = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double d = a.getEntry(i, j) - b.getEntry(i, j);
                s += d * d;
            }
        return Math.sqrt(s / 16.0);
    }
}
