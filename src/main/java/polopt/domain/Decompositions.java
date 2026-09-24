package polopt.domain;

import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;

import java.util.ArrayList;
import java.util.List;

/**
 * Multiple decomposition candidates for a Mueller matrix. Candidates are kept
 * distinct: a non-physical projection is labelled as such and never overwrites
 * the original estimate.
 */
public final class Decompositions {

    public record Candidate(
            String kind,
            String title,
            double[][] matrix,
            boolean physical,
            double minCloudeEigenvalue,
            List<PhysicalRealizability.Violation> violations,
            double diattenuation,
            double retardanceRad,
            double depolarizationIndex) {
    }

    private Decompositions() {
    }

    public static List<Candidate> all(double[][] m) {
        List<Candidate> out = new ArrayList<>();
        out.add(original(m));
        out.add(luChipmanPolar(m));
        out.add(cloudeDepolarizing(m));
        return out;
    }

    public static Candidate original(double[][] m) {
        PhysicalRealizability.Report r = PhysicalRealizability.evaluate(m);
        return new Candidate("ORIGINAL_LS", "Original least-squares estimate",
                m, r.physical(), r.minEigenvalue(), r.violations(),
                diattenuation(m), Double.NaN, depolarizationIndex(m));
    }

    /**
     * Lu-Chipman style polar decomposition M = M_R M_D (forward order). The
     * diattenuator M_D and retarder M_R are derived from the first row/column.
     * For strongly depolarizing/non-physical inputs the factors themselves can
     * be non-physical; this is reported, not hidden.
     */
    public static Candidate luChipmanPolar(double[][] m) {
        double[] mRow = new double[] { m[0][1], m[0][2], m[0][3] };
        double d = Math.sqrt(dot(mRow, mRow)) / Math.max(1e-12, m[0][0]);
        double diat = Math.min(1.0, d);
        double ret = retardanceFromMatrix(m);
        double[][] pol = polarFactor(m);
        PhysicalRealizability.Report r = PhysicalRealizability.evaluate(pol);
        return new Candidate("LU_CHIPMAN_POLAR", "Lu-Chipman polar factor M_R (M = M_R M_D)",
                pol, r.physical(), r.minEigenvalue(), r.violations(),
                diat, ret, Double.NaN);
    }

    private static double[][] polarFactor(double[][] m) {
        SimpleMatrix a = new SimpleMatrix(m);
        SimpleSVD<SimpleMatrix> svd = a.svd(true);
        SimpleMatrix u = svd.getU();
        SimpleMatrix vt = svd.getV().transpose();
        SimpleMatrix p = u.mult(vt);
        double[][] out = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                out[i][j] = p.get(i, j);
            }
        }
        return out;
    }

    private static double retardanceFromMatrix(double[][] m) {
        // Retardance proxy from the 3x3 block (Lu-Chipman retarder):
        // delta = arccos((tr(M_R)-1)/2); here computed on the polar factor.
        double[][] p = polarFactor(m);
        double tr = 0.0;
        for (int i = 1; i < 4; i++) {
            tr += p[i][i];
        }
        double cos = Math.max(-1.0, Math.min(1.0, (tr - 1.0) / 2.0));
        return Math.acos(cos);
    }

    /**
     * Cloude spectral (depolarization) candidate: PSD projection of H.
     * Stored separately and explicitly labelled so the original non-physical
     * candidate is never silently replaced.
     */
    public static Candidate cloudeDepolarizing(double[][] m) {
        double[][] projected = PhysicalRealizability.projectPsd(m);
        PhysicalRealizability.Report r = PhysicalRealizability.evaluate(projected);
        return new Candidate("CLOUDE_PSD_PROJECTION",
                "Cloude PSD (depolarization) projection — derived, not the original",
                projected, r.physical(), r.minEigenvalue(), r.violations(),
                diattenuation(projected), Double.NaN, depolarizationIndex(projected));
    }

    public static double diattenuation(double[][] m) {
        double[] row = { m[0][1], m[0][2], m[0][3] };
        double norm = Math.sqrt(dot(row, row));
        return norm / Math.max(1e-12, Math.abs(m[0][0]));
    }

    public static double depolarizationIndex(double[][] m) {
        double sum = 0.0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                sum += m[i][j] * m[i][j];
            }
        }
        return Math.sqrt(Math.max(0.0, sum / (3.0 * m[0][0] * m[0][0])));
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
