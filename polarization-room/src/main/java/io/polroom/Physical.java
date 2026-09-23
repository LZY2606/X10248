package io.polroom;

import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Physical-realizability checks for a Mueller matrix.
 *
 * A 4x4 real matrix M is a physical Mueller matrix of a passive (non-amplifying) optical
 * element iff the associated 4x4 Hermitian "coherency" matrix H = (1/4) sum M_mu nu
 * (sigma_mu tensor sigma_nu*) is positive semi-definite (Cloude criterion); passivity
 * additionally requires M_00 >= |M_{i,0}| (i=1..3) and, in the transmittance convention
 * used here, m00 <= 1 after normalisation against the reference beam.
 *
 * The checker reports residuals; it NEVER projects a violating candidate and re-presents
 * the projection as the original estimate.
 */
public final class Physical {

    public record ConstraintViolation(String constraint, double value, double limit, double residual) {}

    public record Report(boolean physicallyRealizable, boolean passive, double minEigenvalueH,
                         List<ConstraintViolation> violations, double depolarizationIndex) {}

    private static final double[][] S1 = Pauli.s1();
    private static final double[][] S2 = Pauli.s2();
    private static final double[][] S3 = Pauli.s3();
    private static final double[][] S0 = Pauli.s0();

    private Physical() {}

    /** Cloude coherency matrix H_{ab} = 1/4 sum_{mu,nu} M_{mu nu} tr(sigma_mu sigma_a) tr(sigma_nu* sigma_b). */
    public static RealMatrix coherency(RealMatrix m) {
        double[][][] sigma = {S0, S1, S2, S3};
        RealMatrix h = MatrixUtils.createRealMatrix(4, 4);
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                double val = 0;
                for (int mu = 0; mu < 4; mu++) {
                    for (int nu = 0; nu < 4; nu++) {
                        val += m.getEntry(mu, nu)
                                * tr(sigma[mu], sigma[a])
                                * tr(sigma[nu], sigma[b]);
                    }
                }
                h.setEntry(a, b, val / 4.0);
            }
        }
        return h;
    }

    private static double tr(double[][] a, double[][] b) {
        double t = 0;
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a.length; j++) t += a[i][j] * b[j][i];
        return t;
    }

    public static Report assess(RealMatrix m) {
        List<ConstraintViolation> violations = new ArrayList<>();

        RealMatrix h = coherency(m);
        EigenDecomposition eig = new EigenDecomposition(h);
        double minEig = Double.POSITIVE_INFINITY;
        for (double z : eig.getRealEigenvalues()) minEig = Math.min(minEig, z);
        double eigTol = -1e-7 * Math.max(1.0, LA.frobenius(m) / 4.0);
        if (minEig < eigTol) {
            violations.add(new ConstraintViolation("coherency_PSD", minEig, 0.0, -minEig));
        }

        boolean passive = true;
        double m00 = m.getEntry(0, 0);
        for (int i = 1; i < 4; i++) {
            double mi0 = m.getEntry(i, 0);
            if (Math.abs(mi0) > Math.abs(m00) + 1e-7) {
                passive = false;
                violations.add(new ConstraintViolation("passivity_column0_M" + i + "0",
                        Math.abs(mi0), Math.abs(m00), Math.abs(mi0) - Math.abs(m00)));
            }
        }
        double norm = Math.abs(m00) == 0 ? 1.0 : Math.abs(m00);
        if (m00 / norm > 1.0 + 1e-7) {
            passive = false;
            violations.add(new ConstraintViolation("passivity_m00_le_1", m00 / norm, 1.0, m00 / norm - 1.0));
        }

        Mueller mm = new Mueller(m, Conventions.canonical());
        double di = mm.depolarizationIndex();
        boolean realizable = minEig >= eigTol;
        return new Report(realizable, passive && realizable, minEig, violations, di);
    }

    public static Map<String, Object> toMap(Report r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("physicallyRealizable", r.physicallyRealizable());
        out.put("passive", r.passive());
        out.put("minEigenvalueCoherency", r.minEigenvalueH());
        out.put("depolarizationIndex", r.depolarizationIndex());
        List<Map<String, Object>> vs = new ArrayList<>();
        for (ConstraintViolation v : r.violations()) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("constraint", v.constraint());
            vm.put("value", v.value());
            vm.put("limit", v.limit());
            vm.put("residual", v.residual());
            vs.add(vm);
        }
        out.put("violations", vs);
        return out;
    }
}
