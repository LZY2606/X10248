package polarmueller.core;

/** Physical-realizability checks via the Cloude coherency matrix. */
public final class Physicality {
    public static final double VIOLATION_TOL = -1e-9;

    private Physicality() {}

    // Pauli set {I, sx, sy, sz}
    private static final double[][][] SIG_RE = {
            {{1, 0}, {0, 1}},
            {{0, 1}, {1, 0}},
            {{0, 0}, {0, 0}},
            {{1, 0}, {0, -1}}};
    private static final double[][][] SIG_IM = {
            {{0, 0}, {0, 0}},
            {{0, 0}, {0, 0}},
            {{0, -1}, {1, 0}},
            {{0, 0}, {0, 0}}};

    /** Coherency matrix H = (1/4) sum_ij m_ij (sigma_i (x) sigma_j*). */
    public static double[][][] coherency(double[] m16) {
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double c = m16[i * 4 + j] / 4.0;
                for (int a = 0; a < 2; a++)
                    for (int b = 0; b < 2; b++)
                        for (int p = 0; p < 2; p++)
                            for (int q = 0; q < 2; q++) {
                                // sigma_j* conjugates the imaginary part
                                re[a * 2 + p][b * 2 + q] += c * (SIG_RE[i][a][b] * SIG_RE[j][p][q] + SIG_IM[i][a][b] * SIG_IM[j][p][q]);
                                im[a * 2 + p][b * 2 + q] += c * (-SIG_RE[i][a][b] * SIG_IM[j][p][q] + SIG_IM[i][a][b] * SIG_RE[j][p][q]);
                            }
            }
        return new double[][][]{re, im};
    }

    public static double[] coherencyEigenvalues(double[] m16) {
        double[][][] h = coherency(m16);
        return MatrixOps.hermitianEigenvalues(h[0], h[1]);
    }

    public static double minCoherencyEigenvalue(double[] m16) {
        return coherencyEigenvalues(m16)[0];
    }
}
