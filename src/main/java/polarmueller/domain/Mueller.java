package polarmueller.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller calculus core. Conventions are explicit: Stokes order and rotation
 * convention are carried by the caller and enter the fingerprint; this class
 * only implements the algebra under a stated convention.
 */
public final class Mueller {
    private Mueller() {}

    /** One measurement: analyzer Stokes row a, generator Stokes column s. */
    public record State(double[] analyzer, double[] generator) {}

    /** Design matrix W: each row is kron(a, s) so that I = W * vec(M). */
    public static double[][] designMatrix(List<State> states) {
        double[][] w = LA.mat(states.size(), 16);
        for (int k = 0; k < states.size(); k++) {
            double[] a = states.get(k).analyzer();
            double[] s = states.get(k).generator();
            int col = 0;
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    w[k][col++] = a[i] * s[j];
        }
        return w;
    }

    public record Estimate(
            double conditionNumber,
            boolean singular,
            double[][] matrix,     // null when singular
            double[][] covariance, // null when singular
            String diagnostic) {}

    /**
     * Weighted least squares m = (W' C^-1 W)^-1 W' C^-1 y.
     * A near-singular design is NOT inverted: the estimate is refused and a
     * diagnostic is returned instead.
     */
    public static Estimate estimate(double[][] w, double[] y, double[][] covY, double condThreshold) {
        double[][] wt = LA.transpose(w);
        double[][] cInv = LA.inverse(covY, 1e-300);
        double[][] n = LA.mul(LA.mul(wt, cInv), w);
        double cond = LA.condSym(n);
        if (!(cond < condThreshold)) {
            return new Estimate(cond, true, null, null,
                    "design matrix near-singular: cond=" + cond + " >= threshold " + condThreshold
                            + "; normal equations NOT inverted");
        }
        double[][] covM = LA.inverse(n, 1e-300);
        double[] m = LA.mulVec(LA.mul(covM, LA.mul(wt, cInv)), y);
        double[][] mm = LA.mat(4, 4);
        for (int i = 0; i < 16; i++) mm[i / 4][i % 4] = m[i];
        return new Estimate(cond, false, mm, covM, "ok");
    }

    /** Cloude coherency matrix eigenvalues (ascending). Physical iff all >= 0. */
    public static double[] coherencyEigenvalues(double[][] m) {
        // Hermitian 4x4 H = A + iB mapped to real symmetric 8x8 [[A,-B],[B,A]].
        double[][] re = LA.mat(4, 4);
        double[][] im = LA.mat(4, 4);
        re[0][0] = (m[0][0] + m[0][1] + m[1][0] + m[1][1]) / 2;
        re[0][1] = (m[0][2] + m[1][2]) / 2;             im[0][1] = (m[3][0] - m[3][1]) / 2;
        re[0][2] = (m[0][3] + m[1][3]) / 2;             im[0][2] = (m[2][1] - m[2][0]) / 2;
        re[0][3] = (m[2][2] + m[3][3]) / 2;             im[0][3] = (m[2][3] - m[3][2]) / 2;
        re[1][1] = (m[0][0] - m[0][1] - m[1][0] + m[1][1]) / 2;
        re[1][2] = (m[2][3] + m[3][2]) / 2;             im[1][2] = (m[1][3] - m[0][3]) / 2;
        re[1][3] = (m[2][0] - m[2][1]) / 2;             im[1][3] = (m[0][3] + m[1][3]) / 2;
        re[2][2] = (m[0][0] + m[0][1] - m[1][0] - m[1][1]) / 2;
        re[2][3] = (m[0][1] - m[1][0]) / 2;             im[2][3] = (m[2][3] + m[3][2]) / 2;
        re[3][3] = (m[0][0] - m[0][1] + m[1][0] - m[1][1]) / 2;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < i; j++) { re[i][j] = re[j][i]; im[i][j] = -im[j][i]; }
        double[][] emb = LA.mat(8, 8);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                emb[i][j] = re[i][j];
                emb[i][j + 4] = -im[i][j];
                emb[i + 4][j] = im[i][j];
                emb[i + 4][j + 4] = re[i][j];
            }
        double[] ev = LA.eigSym(emb);
        double[] four = new double[4];
        for (int i = 0; i < 4; i++) four[i] = ev[2 * i]; // each eigenvalue appears twice
        return four;
    }

    public record Physicality(boolean physical, double[] eigenvalues, List<String> violations) {}

    /** Flags violations; never projects the matrix back to the physical cone. */
    public static Physicality physicality(double[][] m, double tol) {
        double[] ev = coherencyEigenvalues(m);
        double scale = Math.max(1.0, Math.abs(ev[3]));
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            if (ev[i] < -tol * scale)
                violations.add("coherency eigenvalue lambda" + i + "=" + ev[i] + " < -" + tol * scale);
        return new Physicality(violations.isEmpty(), ev, violations);
    }

    public record DecompCandidate(
            String label,
            double[][] diattenuator,
            double[][] retarder,
            double[][] depolarizer,
            double diattenuation,
            double retardanceDeg,
            double depolarizationIndex,
            String rotationConvention,
            String diagnostic) {}

    /**
     * Lu-Chipman style decomposition M = M_dep * M_ret * M_dia using the polar
     * factor of the 3x3 submatrix. Multiple legal candidates are returned
     * (forward / reverse order / passive rotation sign); the caller keeps all.
     */
    public static List<DecompCandidate> decompose(double[][] m, String rotationConvention) {
        List<DecompCandidate> out = new ArrayList<>();
        out.add(decomposeOne(m, "lu-chipman-forward", rotationConvention, 1.0));
        double[][] mt = LA.transpose(m);
        DecompCandidate rev = decomposeOne(mt, "reverse-order", rotationConvention, 1.0);
        out.add(new DecompCandidate("reverse-order",
                LA.transpose(rev.diattenuator), LA.transpose(rev.retarder), LA.transpose(rev.depolarizer),
                rev.diattenuation, rev.retardanceDeg, rev.depolarizationIndex,
                rotationConvention, rev.diagnostic + " (via transpose)"));
        DecompCandidate passive = decomposeOne(m, "passive-rotation", rotationConvention, -1.0);
        out.add(passive);
        return out;
    }

    private static DecompCandidate decomposeOne(double[][] m, String label, String convention, double rotSign) {
        try {
            double m00 = m[0][0];
            if (Math.abs(m00) < 1e-12)
                return failed(label, convention, "m00 ~ 0, cannot normalize");
            double[] d = {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
            double pd = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            if (pd >= 1.0)
                return failed(label, convention, "diattenuation " + pd + " >= 1, decomposition undefined");
            double sq = Math.sqrt(1 - pd * pd);
            double[][] mD = LA.mat(4, 4);
            mD[0][0] = 1;
            for (int i = 0; i < 3; i++) { mD[0][i + 1] = d[i]; mD[i + 1][0] = d[i]; }
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    mD[i + 1][j + 1] = sq * (i == j ? 1 : 0)
                            + (1 - sq) * (pd > 1e-12 ? d[i] * d[j] / (pd * pd) : (i == j ? 1 : 0) / 3.0);
            double[][] mDinv = LA.inverse(mD, 1e-14);
            double[][] mPrime = LA.mul(m, mDinv);
            double[][] sub = LA.mat(3, 3);
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) sub[i][j] = mPrime[i + 1][j + 1];
            double[][] n = LA.mul(sub, LA.transpose(sub));
            double[][] mDelta = LA.sqrtSym(n);
            double[][] mR = LA.mul(LA.inverse(mDelta, 1e-14), sub);
            double[][] retarder = LA.identity(4);
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) retarder[i + 1][j + 1] = rotSign * mR[i][j];
            // re-orthogonalize factor for display consistency
            double cosArg = (LA.trace(retarder) - 1) / 2.0;
            cosArg = Math.max(-1, Math.min(1, cosArg));
            double retardanceDeg = Math.toDegrees(Math.acos(cosArg));
            double[][] depol = LA.identity(4);
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) depol[i + 1][j + 1] = mDelta[i][j];
            double[] ev = LA.eigSym(mDelta);
            double depIdx = Math.sqrt(Math.max(0, (ev[0] * ev[0] + ev[1] * ev[1] + ev[2] * ev[2]
                    - (ev[0] + ev[1] + ev[2]) * (ev[0] + ev[1] + ev[2]) / 3.0) * 1.5));
            String conv = rotSign > 0 ? convention : convention + ":passive-sign";
            return new DecompCandidate(label, mD, retarder, depol, pd, retardanceDeg, depIdx, conv, "ok");
        } catch (ArithmeticException ex) {
            return failed(label, convention, ex.getMessage());
        }
    }

    private static DecompCandidate failed(String label, String convention, String why) {
        return new DecompCandidate(label, null, null, null,
                Double.NaN, Double.NaN, Double.NaN, convention, "decomposition failed: " + why);
    }
}
