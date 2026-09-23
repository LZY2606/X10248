package pol.math;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multiple legal decomposition candidates for an estimated Mueller matrix.
 * Every candidate keeps a stable identity; nothing here is chosen silently.
 *
 *  - lu-chipman-forward : M = M_delta M_R M_D  (depolarizer * retarder * diattenuator)
 *  - lu-chipman-reverse : M = M_D M_R M_delta  (reverse multiplication order)
 *  - generic-polar      : M = H * O           (nearest-orthogonal polar decomposition)
 *  - cloude-spectral    : spectral decomposition of the coherency matrix
 */
public final class Decompositions {

    public static final class Candidate {
        public String id;
        public String name;
        public Map<String, double[][]> factors = new LinkedHashMap<>();
        public Map<String, Double> scalars = new LinkedHashMap<>();
        public String note;
        public boolean physical;
    }

    private Decompositions() {}

    public static Map<String, Candidate> all(double[][] m) {
        Map<String, Candidate> out = new LinkedHashMap<>();
        out.put("lu-chipman-forward", luChipman(m, false));
        out.put("lu-chipman-reverse", luChipman(m, true));
        out.put("generic-polar", genericPolar(m));
        out.put("cloude-spectral", cloudeSpectral(m));
        return out;
    }

    /** Simplified Lu-Chipman: diattenuator from first row, retarder via nearest orthogonal of normalized 3x3 block. */
    public static Candidate luChipman(double[][] m, boolean reverse) {
        Candidate c = new Candidate();
        c.id = reverse ? "lu-chipman-reverse" : "lu-chipman-forward";
        c.name = reverse ? "Lu-Chipman reverse M_D M_R M_Δ" : "Lu-Chipman forward M_Δ M_R M_D";

        double m00 = m[0][0] == 0 ? 1 : m[0][0];
        double[] dvec = new double[3];
        double d2 = 0;
        for (int i = 0; i < 3; i++) { dvec[i] = m[0][i + 1] / m00; d2 += dvec[i] * dvec[i]; }
        double diattenuation = Math.sqrt(d2);
        double tx = Math.sqrt(Math.max(0, 1 - d2));
        double[][] md = Mat.create(4, 4);
        md[0] = new double[]{1, dvec[0], dvec[1], dvec[2]};
        for (int i = 1; i < 4; i++) md[i][0] = dvec[i - 1];
        for (int i = 1; i < 4; i++)
            for (int j = 1; j < 4; j++)
                md[i][j] = (i == j ? tx : 0) + dvec[i - 1] * dvec[j - 1] / (1 + tx);
        double[][] mdInv = invert4(md);
        double[][] rest = reverse ? Mat.multiply(m, Mat.identity(4)) : Mat.mult(m, mdInv);

        double[][] mr = Mat.create(4, 4);
        mr[0][0] = 1;
        for (int i = 1; i < 4; i++) mr[i][i] = 1;
        double[][] block = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) block[i][j] = rest[i + 1][j + 1];
        double[][] ortho = Mat.nearestOrthogonal(block);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) mr[i + 1][j + 1] = ortho[i][j];

        double[][] mrInv = transpose4(mr);
        double[][] mdelta = reverse ? Mat.mult(m, transpose4(md)) : Mat.mult(rest, mrInv);

        c.factors.put("diattenuator_M_D", md);
        c.factors.put("retarder_M_R", mr);
        c.factors.put("depolarizer_M_delta", mdelta);
        c.scalars.put("diattenuation", diattenuation);
        double lin = 0, circ = 0;
        lin = Math.acos((ortho[0][0] + ortho[1][1] + ortho[2][2] - 1) / 2.0);
        circ = lin;
        c.scalars.put("retardance_rad", Double.isNaN(lin) ? 0 : lin);
        c.note = reverse
                ? "reverse multiplication order; diattenuator applied first on the beam"
                : "forward multiplication order; depolarizer applied first on the beam";
        c.physical = Physical.evaluate(m).physicallyRealizable;
        return c;
    }

    public static Candidate genericPolar(double[][] m) {
        Candidate c = new Candidate();
        c.id = "generic-polar";
        c.name = "Generic polar decomposition M = H O";
        Mat.SVD s = Mat.svd(m);
        double[][] o = Mat.mult(s.u, s.vt);
        double[][] h = Mat.mult(m, Mat.transpose(o));
        c.factors.put("hermitian_symmetric_H", symmetrize(h));
        c.factors.put("orthogonal_O", o);
        c.scalars.put("H_min_diag_note", 0.0);
        c.note = "nearest-orthogonal factor via SVD; H absorbs depolarization/amplitude";
        c.physical = Physical.evaluate(m).physicallyRealizable;
        return c;
    }

    public static Candidate cloudeSpectral(double[][] m) {
        Candidate c = new Candidate();
        c.id = "cloude-spectral";
        c.name = "Cloude spectral decomposition of coherency T";
        double[] eig = Physical.coherencyEigenvalues(Physical.coherency(m));
        double sum = 0, max = Double.NEGATIVE_INFINITY;
        for (double e : eig) { sum += Math.max(0, e); max = Math.max(max, e); }
        c.scalars.put("lambda0", eig[0]);
        c.scalars.put("lambda1", eig[1]);
        c.scalars.put("lambda2", eig[2]);
        c.scalars.put("lambda3", eig[3]);
        c.scalars.put("purity_lambda_max_over_sum", sum > 0 ? max / sum : Double.NaN);
        c.note = "eigenvalues of the Cloude coherency matrix; negative values mean non-physical input";
        c.physical = eig[0] >= -1e-9;
        return c;
    }

    private static double[][] symmetrize(double[][] a) {
        double[][] o = Mat.copy(a);
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++) { double v = (o[i][j] + o[j][i]) / 2; o[i][j] = v; o[j][i] = v; }
        return o;
    }

    private static double[][] transpose4(double[][] a) {
        double[][] o = Mat.create(4, 4);
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) o[j][i] = a[i][j];
        return o;
    }

    private static double[][] invert4(double[][] a) {
        // prefer general QR inverse
        return Mat.inverse(a);
    }
}
