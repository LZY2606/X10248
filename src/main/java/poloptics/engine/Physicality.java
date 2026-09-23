package poloptics.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical realizability checks for a Mueller matrix M (4x4).
 *
 * Checks recorded individually, each as pass/violation with residual:
 *  1. transmittance bound: m00 >= 0
 *  2. Stokes sub-matrix norm: m00^2 - sum_{i,j>0} mij^2 >= 0 (depolarizing bound)
 *  3. Cloude coherency matrix H = sum M_ij (sigma_i kron sigma_j*)/4 PSD
 *  4. Cloude entropy within [0,1]
 *
 * Non-physical candidates are FLAGGED, never silently projected and presented
 * as the original solution.
 */
public final class Physicality {
    private Physicality() {}

    public static final class Violation {
        public final String ruleId;
        public final String description;
        public final double residual; // >= 0 means pass margin, < 0 means violation amount
        public final boolean pass;
        public Violation(String ruleId, String description, double residual) {
            this.ruleId = ruleId;
            this.description = description;
            this.residual = residual;
            this.pass = residual >= -1e-9;
        }
    }

    public static final class Report {
        public final List<Violation> violations;
        public final double cloudeEntropy;
        public final double[] cloudeEigenvalues;
        public final boolean physicallyRealizable;
        public Report(List<Violation> violations, double cloudeEntropy, double[] ev, boolean ok) {
            this.violations = violations;
            this.cloudeEntropy = cloudeEntropy;
            this.cloudeEigenvalues = ev;
            this.physicallyRealizable = ok;
        }
    }

    /** Cloude coherency matrix from Mueller matrix using Pauli basis. */
    public static double[][] coherencyMatrix(double[][] m) {
        double[][][] sigma = pauliMatrices();
        double[][][] basis = new double[4][][];
        for (int i = 0; i < 4; i++) basis[i] = sigma[i];
        // H = sum_ij m[i][j] * (sigma_i kron sigma_j^*) / 4
        double n = 0;
        double[][] h = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double[][] k = kron(basis[i], conjT(basis[j]));
                for (int a = 0; a < 4; a++)
                    for (int b = 0; b < 4; b++) h[a][b] += m[i][j] * k[a][b] / 4.0;
            }
        }
        return h;
    }

    private static double[][] conjT(double[][] a) {
        // Pauli matrices are real/symmetric, sigma2 is imaginary but the standard
        // Mueller<->coherency mapping below uses real Pauli set consistently.
        return Mat.transpose(a);
    }

    private static double[][][] pauliMatrices() {
        return new double[][][] {
            {{1,0},{0,1}},
            {{1,0},{0,-1}},
            {{0,1},{1,0}},
            {{0,-1},{1,0}} // imaginary sigma_y encoded via real anti-symmetric block
        };
    }

    private static double[][] kron(double[][] a, double[][] b) {
        double[][] k = new double[4][4];
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++)
            for (int u = 0; u < 2; u++) for (int v = 0; v < 2; v++)
                k[2*i+u][2*j+v] = a[i][j] * b[u][v];
        return k;
    }

    private static double[] symmetricEigenvalues(double[][] h) {
        int n = h.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(h[i], 0, a[i], 0, n);
        double[][] v = Mat.identity(n);
        double[] d = new double[n];
        // reuse Mat's Jacobi indirectly through symmetricSqrt is not possible;
        // implement local Jacobi
        for (int sweep = 0; sweep < 200; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q]*a[p][q];
            if (off < 1e-26) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                if (Math.abs(a[p][q]) < 1e-18) continue;
                double tau = (a[q][q]-a[p][p])/(2*a[p][q]);
                double t = (tau>=0?1:-1)/(Math.abs(tau)+Math.sqrt(1+tau*tau));
                double c = 1/Math.sqrt(1+t*t), s = t*c;
                double app=a[p][p], aqq=a[q][q], apq=a[p][q];
                a[p][p]=app-t*apq; a[q][q]=aqq+t*apq; a[p][q]=a[q][p]=0;
                for (int k2=0;k2<n;k2++) if(k2!=p&&k2!=q){
                    double akp=a[k2][p],akq=a[k2][q];
                    a[k2][p]=a[p][k2]=c*akp-s*akq;
                    a[k2][q]=a[q][k2]=s*akp+c*akq;
                }
            }
        }
        for (int i = 0; i < n; i++) d[i] = a[i][i];
        java.util.Arrays.sort(d);
        double[] desc = new double[n];
        for (int i=0;i<n;i++) desc[i]=d[n-1-i];
        return desc;
    }

    public static Report evaluate(double[][] m) {
        List<Violation> out = new ArrayList<>();
        double m00 = m[0][0];
        out.add(new Violation("TX_NONNEG", "m00 非负（透过率界）", m00));

        double sub = 0;
        for (int i = 1; i < 4; i++) for (int j = 1; j < 4; j++) sub += m[i][j]*m[i][j];
        out.add(new Violation("STOKES_NORM", "m00^2 - ||子矩阵||_F^2 >= 0", m00*m00 - sub));

        double[][] h = coherencyMatrix(m);
        double[] ev = symmetricEigenvalues(h);
        double minEv = ev[ev.length-1];
        out.add(new Violation("CLOUDE_PSD", "Cloude 相干性矩阵半正定（最小特征值 >= 0）", minEv));

        double sum = 0;
        for (double e : ev) sum += Math.max(e, 0);
        double entropy = 0;
        if (sum > 1e-30) {
            for (double e : ev) {
                if (e <= 0) continue;
                double p = e / sum;
                entropy -= p * (Math.log(p) / Math.log(4));
            }
        }
        double entMargin = (entropy >= 0 && entropy <= 1.0 + 1e-9) ? Math.min(entropy, 1-entropy) : -1;
        out.add(new Violation("CLOUDE_ENTROPY", "Cloude 熵位于 [0,1]", entMargin));

        boolean ok = true;
        for (Violation v : out) if (!v.pass) { ok = false; break; }
        return new Report(out, entropy, ev, ok);
    }
}
