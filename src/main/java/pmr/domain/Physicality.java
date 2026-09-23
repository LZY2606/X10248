package pmr.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import pmr.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical realizability checks for a Mueller matrix candidate.
 * Violations are recorded; non-physical candidates are NEVER projected back and
 * presented as the original solution.
 */
public final class Physicality {
    private Physicality() {}

    public static final double EIGEN_TOLERANCE = 1e-9;
    public static final double M00_TOLERANCE = 1e-12;

    public record Result(boolean physical, double m00, double diattenuation, double polarizance,
                         double minEigenvalue, double maxImagEigen, double residualNorm,
                         List<String> violations) {

        public ObjectNode toNode(String candidateId) {
            ObjectNode n = Json.M.createObjectNode();
            n.put("candidateId", candidateId);
            n.put("physical", physical);
            n.put("m00", m00);
            n.put("diattenuation", diattenuation);
            n.put("diattenuationUnit", "dimensionless");
            n.put("polarizance", polarizance);
            n.put("polarizanceUnit", "dimensionless");
            n.put("coherencyMinEigenvalue", minEigenvalue);
            n.put("coherencyMaxImagEigenvalue", maxImagEigen);
            n.put("residualNorm", residualNorm);
            n.put("residualUnit", "dimensionless");
            n.putPOJO("violations", violations);
            return n;
        }
    }

    public static Result check(double[][] m) {
        List<String> violations = new ArrayList<>();
        double m00 = m[0][0];
        if (!(m00 > M00_TOLERANCE)) {
            violations.add("M00_NOT_POSITIVE: m00=" + fmt(m00));
        }
        double dSq = 0;
        double pSq = 0;
        for (int k = 1; k < 4; k++) {
            dSq += m[0][k] * m[0][k];
            pSq += m[k][0] * m[k][0];
        }
        double diattenuation = m00 == 0 ? Double.NaN : Math.sqrt(Math.max(dSq, 0)) / Math.abs(m00);
        double polarizance = m00 == 0 ? Double.NaN : Math.sqrt(Math.max(pSq, 0)) / Math.abs(m00);
        if (diattenuation > 1 + 1e-7) {
            violations.add("DIATTENUATION_GT_ONE: D=" + fmt(diattenuation));
        }
        if (polarizance > 1 + 1e-7) {
            violations.add("POLARIZANCE_GT_ONE: P=" + fmt(polarizance));
        }

        var h = Linalg.coherency(m);
        var eig = Linalg.eigenHermitian(h);
        double minEig = eig.real()[0];
        if (eig.maxImagAbs() > 1e-7) {
            violations.add("COHERENCY_NOT_HERMITIAN: maxImagEigen=" + fmt(eig.maxImagAbs()));
        }
        if (minEig < -EIGEN_TOLERANCE) {
            violations.add("COHERENCY_INDEFINITE: minEigenvalue=" + fmt(minEig));
        }

        double residual = 0;
        residual += Math.pow(Math.max(0, diattenuation - 1), 2);
        residual += Math.pow(Math.max(0, polarizance - 1), 2);
        residual += Math.pow(Math.max(0, -minEig), 2);
        if (m00 <= 0) {
            residual += m00 * m00;
        }
        double residualNorm = Math.sqrt(residual);
        boolean physical = violations.isEmpty();
        return new Result(physical, m00, diattenuation, polarizance, minEig,
                eig.maxImagAbs(), residualNorm, violations);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }
}
