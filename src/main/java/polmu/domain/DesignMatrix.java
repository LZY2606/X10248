package polmu.domain;

import polmu.linalg.Lin;

/**
 * Design matrix for Mueller polarimetry: each measurement I_k = a_k^T M g_k
 * contributes one row vec(a_k g_k^T) in row-major Mueller element order.
 */
public final class DesignMatrix {
    public static final double NEAR_SINGULAR_THRESHOLD = 1e8;

    public final double[][] a;      // N x 16
    public final int rows;
    public final double conditionNumber;
    public final boolean nearSingular;

    private DesignMatrix(double[][] a) {
        this.a = a;
        this.rows = a.length;
        double[][] ata = Lin.mul(Lin.transpose(a), a);
        this.conditionNumber = Lin.conditionNumberSym(ata);
        this.nearSingular = !(conditionNumber < NEAR_SINGULAR_THRESHOLD);
    }

    public static DesignMatrix build(double[][] generatorStates, double[][] analyzerStates,
                                     Conventions conv) {
        int n = generatorStates.length;
        double[][] a = new double[n][16];
        int[] perm = conv.permutation();
        for (int k = 0; k < n; k++) {
            double[] g = conv.fromCanonical(generatorStates[k]);
            double[] an = conv.fromCanonical(analyzerStates[k]);
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    a[k][i * 4 + j] = an[i] * g[j];
        }
        return new DesignMatrix(a);
    }
}
