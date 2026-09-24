package polopt.domain;

import org.ejml.simple.SimpleEVD;
import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical realizability review of a Mueller matrix.
 *
 * A Mueller matrix is physically realizable iff its Cloude coherency matrix
 * H is Hermitian positive semidefinite (passive optical element).
 * A non-physical candidate is annotated with the exact violations; it is never
 * PSD-projected in place and passed off as the original solution. Projection,
 * when requested, is returned as a separate, explicitly labelled candidate.
 */
public final class PhysicalRealizability {

    public record Violation(String code, String message, double value, double limit) {
    }

    public record Report(
            boolean physical,
            double[][] cloudeMatrix,
            double[] cloudeEigenvalues,
            double minEigenvalue,
            double m00,
            double passivityResidual,
            List<Violation> violations) {
    }

    public static final double PSD_TOLERANCE = -1.0e-9;

    private PhysicalRealizability() {
    }

    public static Report evaluate(double[][] m) {
        double[][] h = CloudeMap.fromMueller(m);
        SimpleEVD<SimpleMatrix> evd = new SimpleMatrix(h).eig();
        double[] ev = new double[4];
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double val = evd.getEigenvalue(i).getReal();
            ev[i] = val;
            min = Math.min(min, val);
        }
        java.util.Arrays.sort(ev);

        List<Violation> violations = new ArrayList<>();
        if (min < PSD_TOLERANCE) {
            violations.add(new Violation("CLOUDE_NEGATIVE_EIGENVALUE",
                    "Cloude coherency matrix is not positive semidefinite", min, 0.0));
        }
        double m00 = m[0][0];
        if (m00 < -1e-9) {
            violations.add(new Violation("NEGATIVE_M00",
                    "M_00 (maximum transmitted intensity) is negative", m00, 0.0));
        }
        double passivityResidual = 0.0;
        if (m00 > 0) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    if (i == 0 && j == 0) {
                        continue;
                    }
                    double over = Math.abs(m[i][j]) - m00;
                    if (over > 0) {
                        passivityResidual = Math.max(passivityResidual, over);
                    }
                }
            }
            if (passivityResidual > 1e-7) {
                violations.add(new Violation("PASSIVITY",
                        "|M_ij| must not exceed M_00 for a passive element",
                        m00 + passivityResidual, m00));
            }
        }
        return new Report(violations.isEmpty(), h, ev, min, m00,
                passivityResidual, violations);
    }

    /**
     * Explicitly labelled PSD projection (depolarization decomposition route).
     * Never substitutes for the original candidate; stored separately.
     */
    public static double[][] projectPsd(double[][] m) {
        double[][] h = CloudeMap.fromMueller(m);
        SimpleMatrix hm = new SimpleMatrix(h);
        SimpleEVD<SimpleMatrix> evd = hm.eig();
        SimpleMatrix q = new SimpleMatrix(4, 4);
        double[] lam = new double[4];
        for (int i = 0; i < 4; i++) {
            org.ejml.data.DMatrixRMaj vec = evd.getEigenVector(i).getDDRM();
            for (int r = 0; r < 4; r++) {
                q.set(r, i, vec.get(r));
            }
            lam[i] = Math.max(0.0, evd.getEigenvalue(i).getReal());
        }
        SimpleMatrix hProj = new SimpleMatrix(4, 4);
        for (int i = 0; i < 4; i++) {
            hProj = hProj.plus(q.extractVector(false, i)
                    .mult(q.extractVector(false, i).transpose()).scale(lam[i]));
        }
        double[][] hp = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                hp[i][j] = hProj.get(i, j);
            }
        }
        return CloudeMap.toMueller(hp);
    }
}
