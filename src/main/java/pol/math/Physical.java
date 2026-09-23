package pol.math;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Physical realizability of a Mueller matrix via the Cloude coherency matrix.
 *
 * T is the complex Hermitian 4x4 matrix obtained with the Pauli basis
 *   s0=I, s1=diag(1,-1), s2=[[0,1],[1,0]], s3=[[0,-i],[i,0]]
 * using the standard inversion M -> T (Cloude 1986).
 * A Mueller matrix is physically realizable iff m00 > 0, |mij| <= m00 and T is
 * positive semidefinite. Non-physical candidates are flagged with their violations;
 * they are never projected and then presented as the original solution.
 */
public final class Physical {

    public static final class Check {
        public boolean physicallyRealizable;
        public boolean passive;
        public double m00;
        public double minEigenvalue;
        public double[] eigenvalues;
        public double depolarizationIndex;
        public List<String> violations = new ArrayList<>();
        public Map<String, Double> residuals = new LinkedHashMap<>();
    }

    private Physical() {}

    public static Cplx[][] coherency(double[][] m) {
        Cplx I = Cplx.r(1);
        Cplx[][] t = new Cplx[4][4];
        t[0][0] = c(m[0][0] + m[0][1] + m[1][0] + m[1][1]);
        t[0][1] = c(m[0][2] + m[1][2], m[0][3] + m[1][3]);
        t[0][2] = c(m[2][0] + m[2][1], -(m[3][0] + m[3][1]));
        t[0][3] = c(m[2][2] + m[3][3], m[2][3] - m[3][2]);

        t[1][0] = t[0][1].conj();
        t[1][1] = c(m[0][0] - m[0][1] + m[1][0] - m[1][1]);
        t[1][2] = c(m[2][2] - m[3][3], m[2][3] + m[3][2]);
        t[1][3] = c(m[2][0] - m[2][1], -(m[3][0] - m[3][1]));

        t[2][0] = t[0][2].conj();
        t[2][1] = t[1][2].conj();
        t[2][2] = c(m[0][0] + m[0][1] - m[1][0] - m[1][1]);
        t[2][3] = c(m[0][2] - m[1][2], -(m[0][3] - m[1][3]));

        t[3][0] = t[0][3].conj();
        t[3][1] = t[1][3].conj();
        t[3][2] = t[2][3].conj();
        t[3][3] = c(m[0][0] - m[0][1] - m[1][0] + m[1][1]);

        for (int a = 0; a < 4; a++)
            for (int b = 0; b < 4; b++) t[a][b] = t[a][b].mul(new Cplx(0.25, 0));
        return t;
    }

    private static Cplx c(double re) { return new Cplx(re, 0); }
    private static Cplx c(double re, double im) { return new Cplx(re, im); }

    /** Eigenvalues of the Hermitian coherency matrix via realification. */
    public static double[] coherencyEigenvalues(Cplx[][] t) {
        double[][] h = Mat.create(8, 8);
        for (int a = 0; a < 4; a++)
            for (int b = 0; b < 4; b++) {
                h[a][b] = t[a][b].re * 0.25;
                h[a][b + 4] = -t[a][b].im * 0.25;
                h[a + 4][b] = t[a][b].im * 0.25;
                h[a + 4][b + 4] = t[a][b].re * 0.25;
            }
        double[] all = Mat.symmetricEigen(h);
        return new double[]{all[2], all[3], all[4], all[5]};
    }

    public static Check evaluate(double[][] m) {
        Check c = new Check();
        c.m00 = m[0][0];
        Cplx[][] t = coherency(m);
        c.eigenvalues = coherencyEigenvalues(t);
        java.util.Arrays.sort(c.eigenvalues);
        c.minEigenvalue = c.eigenvalues[0];
        c.depolarizationIndex = depolarizationIndex(m);

        if (c.m00 <= 0) c.violations.add("m00 must be positive: " + Mat.fmt(c.m00));
        double bound = Math.abs(c.m00);
        double worstBound = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                worstBound = Math.max(worstBound, Math.abs(m[i][j]) - bound);
                if (Math.abs(m[i][j]) > bound + 1e-9)
                    c.violations.add("|m" + i + j + "| exceeds |m00|: " + Mat.fmt(m[i][j]));
            }
        double negTol = 1e-9 * Math.max(1.0, Math.abs(c.m00));
        if (c.minEigenvalue < -negTol)
            c.violations.add("Cloude coherency minimum eigenvalue negative: " + Mat.fmt(c.minEigenvalue));

        double maxGain = worstBound + bound;
        c.passive = maxGain <= c.m00 + 1e-9;
        if (!c.passive) c.violations.add("passivity violated: max |mij| > m00");

        c.residuals.put("min_coherency_eigenvalue", c.minEigenvalue);
        c.residuals.put("m00", c.m00);
        c.residuals.put("depolarization_index", c.depolarizationIndex);
        c.residuals.put("worst_element_bound_violation", worstBound);
        c.physicallyRealizable = c.violations.isEmpty();
        return c;
    }

    public static double depolarizationIndex(double[][] m) {
        double s = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) s += m[i][j] * m[i][j];
        if (m[0][0] == 0) return Double.NaN;
        return Math.sqrt(s) / (Math.sqrt(3) * Math.abs(m[0][0]));
    }
}
