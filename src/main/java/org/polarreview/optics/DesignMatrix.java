package org.polarreview.optics;

/**
 * Measurement design matrix W for a complete Mueller polarimeter.
 *
 * <pre>
 *   I_k = g_k * W_k * vec(M) ,   W_k = (S_psa,k)^T \u2297 (S_psg,k)^T
 * </pre>
 *
 * with {@code vec(M)} taken row-major (M00..M03,M10..M33), which matches the
 * Kronecker ordering {@code a_i g_j -> M[i][j]}.
 */
public final class DesignMatrix {
    public final double[][] w;
    public final int[] generatorIndex;
    public final int[] analyzerIndex;

    private DesignMatrix(double[][] w, int[] generatorIndex, int[] analyzerIndex) {
        this.w = w;
        this.generatorIndex = generatorIndex;
        this.analyzerIndex = analyzerIndex;
    }

    /**
     * @param pairs    each row is {generatorStateIndex, analyzerStateIndex}
     * @param psgStokes canonical-order generator Stokes vectors
     * @param psaStokes canonical-order analyzer Stokes vectors
     * @param gain     per-measurement overall gain (reference/dark normalization), or null
     */
    public static DesignMatrix build(int[][] pairs, double[][] psgStokes, double[][] psaStokes,
                                     double[] gain) {
        int n = pairs.length;
        double[][] w = new double[n][16];
        int[] gi = new int[n];
        int[] ai = new int[n];
        for (int k = 0; k < n; k++) {
            gi[k] = pairs[k][0];
            ai[k] = pairs[k][1];
            double[] g = psgStokes[pairs[k][0]];
            double[] a = psaStokes[pairs[k][1]];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    double gg = gain != null ? gain[k] : 1.0;
                    w[k][4 * i + j] = gg * a[i] * g[j];
                }
            }
        }
        return new DesignMatrix(w, gi, ai);
    }

    public int rows() { return w.length; }
}
