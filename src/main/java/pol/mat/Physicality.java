package pol.mat;

import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical realizability audit for Mueller candidates.
 *
 * <p>Checks (each carries a stable check id used by the UI and tests):
 * <ul>
 *   <li>{@code M00} — M00 &gt; 0 (normalising anchor)</li>
 *   <li>{@code CLoude-PSD} — Cloude coherency eigenvalues non-negative</li>
 *   <li>{@code Bounds-Mij} — |Mij| &lt;= M00 element-wise</li>
 * </ul>
 * This class reports violations; it never projects the candidate.
 */
public final class Physicality {
    private Physicality() {}

    public static final double TOLERANCE = 1e-9;

    public static Report audit(RealMatrix m) {
        List<Violation> violations = new ArrayList<>();
        double m00 = m.getEntry(0, 0);
        if (!(m00 > 0)) {
            violations.add(new Violation("M00",
                    "M00 must be strictly positive; got " + fmt(m00), m00));
        }
        double[] ev = Coherency.eigenvalues(m);
        double min = Double.POSITIVE_INFINITY;
        for (double v : ev) {
            min = Math.min(min, v);
        }
        if (min < -TOLERANCE) {
            violations.add(new Violation("Cloude-PSD",
                    "Minimum coherency eigenvalue " + fmt(min) + " is negative", min));
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                double v = m.getEntry(i, j);
                if (Math.abs(v) > m00 + TOLERANCE) {
                    violations.add(new Violation("Bounds-Mij",
                            "|M" + i + j + "| = " + fmt(Math.abs(v))
                                    + " exceeds M00 = " + fmt(m00), v));
                }
            }
        }
        return new Report(ev, violations);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }

    public static final class Report {
        public final double[] coherencyEigenvalues;
        public final List<Violation> violations;

        public Report(double[] coherencyEigenvalues, List<Violation> violations) {
            this.coherencyEigenvalues = coherencyEigenvalues;
            this.violations = violations;
        }

        public boolean physical() {
            return violations.isEmpty();
        }
    }

    public static final class Violation {
        public final String checkId;
        public final String message;
        public final double observed;

        public Violation(String checkId, String message, double observed) {
            this.checkId = checkId;
            this.message = message;
            this.observed = observed;
        }
    }
}
