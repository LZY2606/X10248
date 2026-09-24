package pol.polar;

import pol.linalg.Linear;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Mueller-matrix estimation and review rules.
 * Conventions (Stokes order, rotation sign) are explicit and enter every fingerprint.
 */
public final class Polarimetry {
    private Polarimetry() {}

    /** Condition-number guard: above this the design matrix is never inverted. */
    public static final double CONDITION_GUARD = 1e12;
    public static final double PHYSICALITY_TOL = 1e-9;

    public record Convention(String stokesOrder, String rotationSign, String beamBasis) {
        public String canonical() { return stokesOrder + "|" + rotationSign + "|" + beamBasis; }
    }

    public record Measurement(double[] analyzer, double[] generator, double intensity, double variance) {}

    public enum EstimateStatus { OK, NEAR_SINGULAR }

    public record Estimate(
            EstimateStatus status,
            double conditionNumber,
            double[] eigenvaluesNormal,
            double[][] mueller,        // null when NEAR_SINGULAR: never force-inverted
            double[] elementErrors,    // null when NEAR_SINGULAR
            String diagnostic) {}

    public record Physicality(boolean physical, double[] coherencyEigenvalues, double worstResidual) {}

    public record DecompositionCandidate(
            String rule, double[][] diattenuator, double[][] retarder, double[][] depolarizer,
            double residualNorm, boolean legal, String note) {}

    /** Design matrix: row k = analyzer_k (x) generator_k, vec row-major. */
    public static double[][] designMatrix(List<Measurement> ms) {
        double[][] a = new double[ms.size()][16];
        for (int k = 0; k < ms.size(); k++) {
            Measurement m = ms.get(k);
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    a[k][i * 4 + j] = m.analyzer()[i] * m.generator()[j];
        }
        return a;
    }

    public static double conditionNumber(List<Measurement> ms) {
        double[][] a = designMatrix(ms);
        double[][] n = Linear.mul(Linear.transpose(a), a);
        double[] ev = Linear.eigenvaluesSymmetric(n);
        double min = Math.max(ev[0], 0.0);
        double max = ev[ev.length - 1];
        if (min <= 0) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    /**
     * Weighted least squares. Near-singular designs are reported, never inverted.
     */
    public static Estimate estimate(List<Measurement> ms) {
        double[][] a = designMatrix(ms);
        double[][] w = new double[ms.size()][ms.size()];
        for (int k = 0; k < ms.size(); k++) w[k][k] = 1.0 / ms.get(k).variance();
        double[] y = new double[ms.size()];
        for (int k = 0; k < ms.size(); k++) y[k] = ms.get(k).intensity();
        double[][] at = Linear.transpose(a);
        double[][] n = Linear.mul(at, Linear.mul(w, a));
        double[] ev = Linear.eigenvaluesSymmetric(n);
        double cond = ev[0] <= 0 ? Double.POSITIVE_INFINITY : ev[ev.length - 1] / ev[0];
        if (!(cond < CONDITION_GUARD)) {
            return new Estimate(EstimateStatus.NEAR_SINGULAR, cond, ev, null, null,
                    "design matrix near-singular (cond=" + cond + "); inversion refused by rule est.guard.v1");
        }
        double[][] nInv;
        try {
            nInv = Linear.inverse(n, 1e-15);
        } catch (Linear.SingularException e) {
            return new Estimate(EstimateStatus.NEAR_SINGULAR, cond, ev, null, null,
                    "normal-equations inversion refused: " + e.getMessage());
        }
        double[] m = Linear.mulVec(Linear.mul(nInv, at), Linear.mulVec(w, y));
        double[][] mueller = new double[4][4];
        double[] errs = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                mueller[i][j] = m[i * 4 + j];
                errs[i * 4 + j] = Math.sqrt(Math.max(nInv[i * 4 + j][i * 4 + j], 0.0));
            }
        return new Estimate(EstimateStatus.OK, cond, ev, mueller, errs, "ok");
    }

    /** Coherency-matrix physicality test; violations are reported, never projected away. */
    public static Physicality physicality(double[][] m) {
        // C = 1/2 * sum_ij M_ij (sigma_i (x) sigma_j) with Pauli set
        double[][][] p = pauli();
        double[][] c = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int r = 0; r < 4; r++)
                    for (int s = 0; s < 4; s++)
                        c[r][s] += 0.5 * m[i][j] * p[i][r][s] * p[j][s][r];
        double[] ev = Linear.eigenvaluesSymmetric(c);
        double worst = ev[0];
        return new Physicality(worst >= -PHYSICALITY_TOL, ev, worst);
    }

    private static double[][][] pauli() {
        return new double[][][]{
                {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}},
                {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, -1, 0}, {0, 0, 0, -1}},
                {{1, 0, 0, 0}, {0, 0, 1, 0}, {0, 1, 0, 0}, {0, 0, 0, -1}},
                {{1, 0, 0, 0}, {0, 0, 0, -1}, {0, 0, 1, 0}, {0, 0, 0, 0}}
        };
    }

    /**
     * Lu-Chipman style decomposition, both factor orders. Every candidate whose
     * reconstruction residual is below tolerance stays legal; ambiguity is kept,
     * never silently resolved.
     */
    public static List<DecompositionCandidate> decompose(double[][] m) {
        List<DecompositionCandidate> out = new ArrayList<>();
        double m00 = m[0][0];
        double[] d = {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
        double[] p = {m[1][0] / m00, m[2][0] / m00, m[3][0] / m00};
        double[][] mdD = diattenuator(d);
        double[][] mdP = diattenuator(p);
        // forward order: M = M' * MD  ->  M' = M * MD^-1 ; split M' into retarder+depolarizer
        double[][] mRest = safeRest(m, mdD);
        double[][] mr = retarderPart(mRest);
        double[][] mDelta = depolarizerPart(mRest, mr);
        double res1 = residual(m, Linear.mul(mDelta, Linear.mul(mr, mdD)));
        boolean amb = norm(d) < 1e-6 && norm(p) < 1e-6;
        out.add(new DecompositionCandidate("lu-chipman.forward.v1", mdD, mr, mDelta, res1,
                res1 < 0.05, amb ? "diattenuation~0: order not uniquely decidable; kept as legal candidate" : "forward order"));
        // reverse order: M = MD * M'
        double[][] mRest2 = safeRestReverse(m, mdP);
        double[][] mr2 = retarderPart(mRest2);
        double[][] mDelta2 = depolarizerPart(mRest2, mr2);
        double res2 = residual(m, Linear.mul(mdP, Linear.mul(mr2, mDelta2)));
        out.add(new DecompositionCandidate("lu-chipman.reverse.v1", mdP, mr2, mDelta2, res2,
                res2 < 0.05, amb ? "diattenuation~0: order not uniquely decidable; kept as legal candidate" : "reverse order"));
        return out;
    }

    private static double norm(double[] v) { double s = 0; for (double x : v) s += x * x; return Math.sqrt(s); }

    private static double[][] diattenuator(double[] d) {
        double dn = norm(d);
        double[][] m = new double[4][4];
        double q = Math.sqrt(Math.max(1 - dn * dn, 0.0));
        m[0][0] = 1;
        for (int i = 0; i < 3; i++) { m[0][1 + i] = d[i]; m[1 + i][0] = d[i]; }
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double delta = i == j ? 1 : 0;
                m[1 + i][1 + j] = q * delta + (1 - q) * (dn < 1e-12 ? delta : d[i] * d[j] / (dn * dn));
            }
        return m;
    }

    private static double[][] safeRest(double[][] m, double[][] md) {
        try { return Linear.mul(m, Linear.inverse(md, 1e-14)); }
        catch (Linear.SingularException e) { return m; }
    }

    private static double[][] safeRestReverse(double[][] m, double[][] md) {
        try { return Linear.mul(Linear.inverse(md, 1e-14), m); }
        catch (Linear.SingularException e) { return m; }
    }

    /** Retarder approximation: normalized 3x3 submatrix projected onto rotation-ish form. */
    private static double[][] retarderPart(double[][] rest) {
        double[][] mr = Linear.identity(4);
        double trace = 0;
        for (int i = 1; i < 4; i++) trace += rest[i][i];
        double c = Math.max(-1, Math.min(1, (trace / Math.max(rest[0][0], 1e-12) - 1) / 2));
        double delta = Math.acos(c);
        double s2 = 2 * Math.sin(delta);
        if (Math.abs(s2) > 1e-9) {
            double a = (rest[3][2] - rest[2][3]) / s2;
            double b = (rest[1][3] - rest[3][1]) / s2;
            double g = (rest[2][1] - rest[1][2]) / s2;
            double n = Math.sqrt(a * a + b * b + g * g);
            if (n > 1e-9) {
                a /= n; b /= n; g /= n;
                double cd = Math.cos(delta), sd = Math.sin(delta);
                mr[1][1] = cd + a * a * (1 - cd); mr[1][2] = a * b * (1 - cd) - g * sd; mr[1][3] = a * g * (1 - cd) + b * sd;
                mr[2][1] = b * a * (1 - cd) + g * sd; mr[2][2] = cd + b * b * (1 - cd); mr[2][3] = b * g * (1 - cd) - a * sd;
                mr[3][1] = g * a * (1 - cd) - b * sd; mr[3][2] = g * b * (1 - cd) + a * sd; mr[3][3] = cd + g * g * (1 - cd);
            }
        }
        return mr;
    }

    private static double[][] depolarizerPart(double[][] rest, double[][] mr) {
        try { return Linear.mul(rest, Linear.inverse(mr, 1e-14)); }
        catch (Linear.SingularException e) { return rest; }
    }

    private static double residual(double[][] m, double[][] recon) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) r[i][j] = m[i][j] - recon[i][j];
        return Linear.frobenius(r);
    }

    /** Fingerprint binds rule version, conventions and input content hashes. */
    public static String fingerprint(String ruleVersion, Convention c, List<String> inputShas) {
        StringBuilder sb = new StringBuilder();
        sb.append("rule=").append(ruleVersion).append(';');
        sb.append("stokesOrder=").append(c.stokesOrder()).append(';');
        sb.append("rotationSign=").append(c.rotationSign()).append(';');
        sb.append("beamBasis=").append(c.beamBasis()).append(';');
        inputShas.stream().sorted().forEach(s -> sb.append("in=").append(s).append(';'));
        return "fp-" + sha256(sb.toString()).substring(0, 24);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
