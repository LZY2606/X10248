package lab.polarimetry.mmstudio.math;

import org.apache.commons.math3.linear.*;

import java.util.Arrays;

/**
 * Physical realizability checks for a Mueller matrix.
 * Checks are diagnostics only: a violating candidate is flagged, never projected
 * and then presented as if it were the original estimate.
 */
public final class PhysicalConstraints {
    private PhysicalConstraints() {}

    public record Constraint(String id, String name, String rule, double residual,
                             boolean satisfied, String detail) {}

    public record Report(boolean physicallyRealizable, double cloudeMinEigenvalue,
                         double cloudeMaxEigenvalue, Constraint[] constraints) {}

    public static Report evaluate(RealMatrix m) {
        double[] eig = Pauli.coherencyEigenvalues(m);
        double lmin = Arrays.stream(eig).min().orElse(Double.NaN);
        double lmax = Arrays.stream(eig).max().orElse(Double.NaN);
        double tol = 1e-7 * Math.max(1.0, Math.abs(lmax));

        Constraint c1 = new Constraint(
                "cloude_psd", "Cloude coherency PSD",
                "min eigenvalue(lambda) >= -tol of R = 1/8 sum M_ab Tr(si sa sj sb)",
                lmin, lmin >= -tol,
                String.format("min eigenvalue=%.6g, max eigenvalue=%.6g, tol=%.3g", lmin, lmax, tol));

        double m00 = m.getEntry(0, 0);
        Constraint c2 = new Constraint(
                "passive_m00", "Passive mean transmittance",
                "0 <= M_00 <= 1 (normalized Mueller)",
                m00 < 0 ? m00 : m00 - 1.0,
                m00 >= -tol && m00 <= 1.0 + tol,
                String.format("M00=%.6g", m00));

        double rowNorm = 0, colNorm = 0;
        for (int i = 1; i < 4; i++) {
            rowNorm += m.getEntry(0, i) * m.getEntry(0, i);
            colNorm += m.getEntry(i, 0) * m.getEntry(i, 0);
        }
        double rowViol = Math.sqrt(rowNorm) - m00;
        double colViol = Math.sqrt(colNorm) - m00;
        Constraint c3 = new Constraint(
                "diattenuation_bounds", "Diattenuation bounds",
                "sqrt(M_01^2+M_02^2+M_03^2) <= M_00 ; same on column",
                Math.max(rowViol, colViol),
                rowViol <= tol && colViol <= tol,
                String.format("row excess=%.6g, column excess=%.6g", rowViol, colViol));

        // Mueller matrix 1-norm bound for passive media: every |column|_1 <= M00
        double worst = -Double.MAX_VALUE;
        int worstCol = -1;
        for (int j = 0; j < 4; j++) {
            double sum = 0;
            for (int i = 0; i < 4; i++) sum += Math.abs(m.getEntry(i, j));
            if (sum > worst) { worst = sum; worstCol = j; }
        }
        Constraint c4 = new Constraint(
                "column_l1_bound", "Passive column L1 bound",
                "for every column j, sum_i |M_ij| <= 2 M00 (passive-system test)",
                worst - 2 * m00,
                worst <= 2 * m00 + tol,
                String.format("worst column=%d, sum|column|=%.6g, 2*M00=%.6g", worstCol, worst, 2 * m00));

        boolean ok = c1.satisfied() && c2.satisfied() && c3.satisfied() && c4.satisfied();
        return new Report(ok, lmin, lmax, new Constraint[]{c1, c2, c3, c4});
    }
}
