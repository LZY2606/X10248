package pol.mat;

/** Minimal dense complex matrix used for the 4x4 Cloude coherency algebra. */
public final class CM {
    public final int n;
    public final double[][] re;
    public final double[][] im;

    public CM(int n) {
        this.n = n;
        this.re = new double[n][n];
        this.im = new double[n][n];
    }

    public static CM of(double[][] re, double[][] im) {
        CM c = new CM(re.length);
        for (int i = 0; i < c.n; i++) {
            System.arraycopy(re[i], 0, c.re[i], 0, c.n);
            System.arraycopy(im[i], 0, c.im[i], 0, c.n);
        }
        return c;
    }

    public static CM identity(int n) {
        CM c = new CM(n);
        for (int i = 0; i < n; i++) {
            c.re[i][i] = 1;
        }
        return c;
    }

    public static CM kron(CM a, CM b) {
        int n = a.n * b.n;
        CM c = new CM(n);
        for (int i = 0; i < a.n; i++) {
            for (int j = 0; j < a.n; j++) {
                for (int u = 0; u < b.n; u++) {
                    for (int v = 0; v < b.n; v++) {
                        int r = i * b.n + u;
                        int s = j * b.n + v;
                        c.re[r][s] = a.re[i][j] * b.re[u][v] - a.im[i][j] * b.im[u][v];
                        c.im[r][s] = a.re[i][j] * b.im[u][v] + a.im[i][j] * b.re[u][v];
                    }
                }
            }
        }
        return c;
    }

    /** Tr(a^dagger * b). */
    public static double[] traceAdagB(CM a, CM b) {
        double r = 0;
        double im = 0;
        for (int i = 0; i < a.n; i++) {
            for (int j = 0; j < a.n; j++) {
                double ar = a.re[j][i];
                double ai = -a.im[j][i];
                r += ar * b.re[j][i] - ai * b.im[j][i];
                im += ar * b.im[j][i] + ai * b.re[j][i];
            }
        }
        return new double[] {r, im};
    }

    public static CM dagger(CM a) {
        CM c = new CM(a.n);
        for (int i = 0; i < a.n; i++) {
            for (int j = 0; j < a.n; j++) {
                c.re[i][j] = a.re[j][i];
                c.im[i][j] = -a.im[j][i];
            }
        }
        return c;
    }

    /**
     * Hermitian eigenproblem via the real symmetric 2n x 2n embedding
     * [[Re H, -Im H],[Im H, Re H]]. Every complex eigenpair (lambda, u+iv) appears as a
     * pair of real eigenvectors (u, v) and (-v, u); the doubled eigenspace is orthonormalised
     * to recover the complex vectors.
     */
    public static Eigh eighHermitian(CM h) {
        int n = h.n;
        double[][] a = new double[2 * n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = h.re[i][j];
                a[i + n][j + n] = h.re[i][j];
                a[i][j + n] = -h.im[i][j];
                a[i + n][j] = h.im[i][j];
            }
        }
        org.apache.commons.math3.linear.EigenDecomposition eig =
                new org.apache.commons.math3.linear.EigenDecomposition(
                        org.apache.commons.math3.linear.MatrixUtils.createRealMatrix(a));
        double[] raw = eig.getRealEigenvalues();
        boolean[] used = new boolean[2 * n];
        Integer[] order = new Integer[2 * n];
        for (int i = 0; i < 2 * n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(raw[y], raw[x]));

        double[] ev = new double[n];
        CM vecs = new CM(n);
        int out = 0;
        for (int oi = 0; oi < 2 * n && out < n; oi++) {
            int idx = order[oi];
            if (used[idx]) {
                continue;
            }
            double lambda = raw[idx];
            List<Integer> group = new java.util.ArrayList<>();
            for (int oj = oi; oj < 2 * n; oj++) {
                int k = order[oj];
                if (!used[k] && Math.abs(raw[k] - lambda) <= 1e-9 * Math.max(1, Math.abs(lambda))) {
                    group.add(k);
                }
            }
            List<double[]> basis = new java.util.ArrayList<>();
            for (int k : group) {
                basis.add(eig.getEigenvector(k).toArray());
            }
            int pairs = Math.min(n - out, group.size() / 2);
            // Each complex eigenvector needs two orthogonal real vectors from the group.
            for (int t = 0; t < pairs; t++) {
                double[] u = normalize(basis.get(2 * t));
                double[] v = normalize(projectOrthogonal(basis.get(2 * t + 1), basis, 2 * t + 1));
                ev[out] = lambda;
                for (int k = 0; k < n; k++) {
                    vecs.re[k][out] = u[k];
                    vecs.im[k][out] = v[k];
                }
                out++;
            }
            for (int k : group) {
                used[k] = true;
            }
        }
        // Defensive: complete remaining slots (should not occur for Hermitian input).
        while (out < n) {
            ev[out] = 0;
            vecs.re[out][out] = 1;
            out++;
        }
        return new Eigh(ev, vecs);
    }

    private static double[] normalize(double[] x) {
        double n = 0;
        for (double v : x) {
            n += v * v;
        }
        n = Math.sqrt(n);
        double[] y = x.clone();
        for (int i = 0; i < y.length; i++) {
            y[i] /= n;
        }
        return y;
    }

    private static double[] projectOrthogonal(double[] x, List<double[]> basis, int count) {
        double[] y = x.clone();
        for (int t = 0; t < count; t++) {
            double[] e = normalize(basis.get(t));
            double dot = 0;
            for (int i = 0; i < y.length; i++) {
                dot += y[i] * e[i];
            }
            for (int i = 0; i < y.length; i++) {
                y[i] -= dot * e[i];
            }
        }
        double n = 0;
        for (double v : y) {
            n += v * v;
        }
        if (n < 1e-14) {
            // Rare: identical numerical vectors; find any un-used coordinate direction.
            outer:
            for (int c = 0; c < y.length; c++) {
                double[] e = new double[y.length];
                e[c] = 1;
                for (int t = 0; t < count; t++) {
                    double[] b = normalize(basis.get(t));
                    double dot = 0;
                    for (int i = 0; i < y.length; i++) {
                        dot += e[i] * b[i];
                    }
                    for (int i = 0; i < y.length; i++) {
                        e[i] -= dot * b[i];
                    }
                }
                double nn = 0;
                for (double v : e) {
                    nn += v * v;
                }
                if (nn > 1e-10) {
                    y = e;
                    break outer;
                }
            }
        }
        return y;
    }

    private static int[] descIndices(double[] v) {
        int[] idx = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            idx[i] = i;
        }
        for (int i = 0; i < v.length; i++) {
            for (int j = i + 1; j < v.length; j++) {
                if (v[idx[j]] > v[idx[i]]) {
                    int t = idx[i];
                    idx[i] = idx[j];
                    idx[j] = t;
                }
            }
        }
        return idx;
    }

    public static final class Eigh {
        public final double[] values;
        public final CM vectors;

        public Eigh(double[] values, CM vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }
}
