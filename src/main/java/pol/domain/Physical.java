package pol.domain;

import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical-realizability residuals for a Mueller candidate. A candidate that
 * violates a constraint is tagged with the violation; it is never projected
 * onto the physical set and presented as the original solution.
 */
public final class Physical {

    public static final class Violation {
        public final String name;
        public final double amount;
        public final String detail;

        public Violation(String name, double amount, String detail) {
            this.name = name;
            this.amount = amount;
            this.detail = detail;
        }
    }

    public static final class Report {
        public final double minCoherencyEigenvalue;
        public final double entropy;
        public final double depolarizationIndex;
        public final double m00;
        public final boolean passivityOk;
        public final boolean stokesBoundsOk;
        public final boolean coherencyPsd;
        public final List<Violation> violations;
        public final boolean realizable;

        Report(double minEig, double entropy, double di, double m00,
               boolean passivity, boolean bounds, boolean psd, List<Violation> violations) {
            this.minCoherencyEigenvalue = minEig;
            this.entropy = entropy;
            this.depolarizationIndex = di;
            this.m00 = m00;
            this.passivityOk = passivity;
            this.stokesBoundsOk = bounds;
            this.coherencyPsd = psd;
            this.violations = violations;
            double tol = 1e-7;
            this.realizable = minEig >= -tol && passivity && bounds;
        }
    }

    private Physical() {
    }

    public static Report check(double[][] m) {
        List<Violation> violations = new ArrayList<>();
        Cloude.Coherency coh = Cloude.analyze(m);
        double minEig = coh.minEigenvalue;
        if (minEig < -1e-7) {
            violations.add(new Violation("coherency-not-PSD", -minEig,
                    "smallest Cloude eigenvalue " + Qty.format(minEig) + " < 0"));
        }

        double m00 = m[0][0];
        boolean passivity = true;
        if (m00 < -1e-9) {
            violations.add(new Violation("negative-transmittance", -m00,
                    "M00=" + Qty.format(m00) + " < 0"));
            passivity = false;
        }
        if (m00 > 1.0 + 1e-7) {
            violations.add(new Violation("passivity-gain", m00 - 1.0,
                    "M00=" + Qty.format(m00) + " > 1 (normalized incident intensity)"));
            passivity = false;
        }

        double sum = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (!(i == 0 && j == 0)) {
                    sum += m[i][j] * m[i][j];
                }
            }
        }
        double di = Math.sqrt(sum) / (Math.sqrt(3.0) * Math.abs(m00) + 1e-300);
        if (di > 1.0 + 1e-7) {
            violations.add(new Violation("depolarization-index>1", di - 1.0,
                    "DI=" + Qty.format(di)));
        }

        boolean bounds = true;
        for (int k = 1; k < 4; k++) {
            double rowNorm = 0;
            double colNorm = 0;
            for (int t = 1; t < 4; t++) {
                rowNorm += m[k][t] * m[k][t];
                colNorm += m[t][k] * m[t][k];
            }
            rowNorm = Math.sqrt(rowNorm);
            colNorm = Math.sqrt(colNorm);
            if (rowNorm > Math.abs(m[k][0]) + 1e-7) {
                bounds = false;
                violations.add(new Violation("stokes-row-bound-" + k,
                        rowNorm - Math.abs(m[k][0]),
                        "|row " + k + "[1..3]|=" + Qty.format(rowNorm)
                                + " > |M[" + k + "][0]|=" + Qty.format(m[k][0])));
            }
            if (colNorm > Math.abs(m[0][k]) + 1e-7) {
                bounds = false;
                violations.add(new Violation("stokes-column-bound-" + k,
                        colNorm - Math.abs(m[0][k]),
                        "|col " + k + "[1..3]|=" + Qty.format(colNorm)
                                + " > |M[0][" + k + "]|=" + Qty.format(m[0][k])));
            }
        }

        double ent = Cloude.entropy(coh.eigenvalues());
        return new Report(minEig, ent, di, m00, passivity, bounds, minEig >= -1e-7, violations);
    }
}
