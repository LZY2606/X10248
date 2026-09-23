package org.polarroom.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller/Stokes physical primitives.
 *
 * <p>Conventions: Stokes vectors are stored in the canonical order
 * (S0, S1, S2, S3) regardless of how they were recorded on disk. Re-ordering
 * happens at the import/derived layer and is tracked in the fingerprint.
 */
public final class Mueller {

    private Mueller() {
    }

    /** Pauli spin matrices used for the Cloude coherency expansion. */
    private static final double[][][] SIGMA = new double[][][]{
            {{1, 0}, {0, 1}},
            {{1, 0}, {0, -1}},
            {{0, 1}, {1, 0}},
            {{0, -1}, {1, 0}}
    };

    private static final double S = 1.0 / Math.sqrt(2.0);

    /**
     * Cloude coherency matrix H = sum_{i,j} M_{ij} A_i \otimes A_j^* / 2
     * where A_0 = I, A_1 = sigma_z, A_2 = sigma_x, A_3 = i sigma_y.
     */
    public static void coherency(double[][] m, double[][] reOut, double[][] imOut) {
        double[][][] a = new double[4][2][2];
        double[][][] ai = new double[4][2][2];
        for (int k = 0; k < 4; k++) {
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    a[k][r][c] = S * SIGMA[k][r][c];
                    // A_3 = i sigma_y carries the imaginary unit.
                    ai[k][r][c] = S * (k == 3 ? SIGMA[k][r][c] : 0.0);
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double re = 0.0;
                double im = 0.0;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        double zr = a[i][r][c];
                        double zi = ai[i][r][c];
                        double wr = a[j][r][c];
                        double wi = ai[j][r][c];
                        // z * conj(w)
                        re += zr * wr + zi * wi;
                        im += zi * wr - zr * wi;
                    }
                }
                reOut[i][j] = m[i][j] * re;
                imOut[i][j] = m[i][j] * im;
            }
        }
    }

    /** Reconstruct M from a coherency matrix (inverse of {@link #coherency}). */
    public static double[][] fromCoherency(double[][] re, double[][] im) {
        double[][][] a = new double[4][2][2];
        double[][][] ai = new double[4][2][2];
        for (int k = 0; k < 4; k++) {
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    a[k][r][c] = S * SIGMA[k][r][c];
                    ai[k][r][c] = S * (k == 3 ? SIGMA[k][r][c] : 0.0);
                }
            }
        }
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double v = 0.0;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        double zr = a[i][r][c];
                        double zi = ai[i][r][c];
                        double wr = a[j][r][c];
                        double wi = ai[j][r][c];
                        // conj(z) * w
                        v += (zr * wr + zi * wi) * re[i][j]
                                + (zr * wi - zi * wr) * im[i][j];
                    }
                }
                m[i][j] = v;
            }
        }
        return m;
    }

    /** Physical-realizability diagnostics for a (raw, unnormalised) M. */
    public static final class PhysicalReport {
        public boolean physicallyRealizable;
        public boolean passive;
        public double[] coherencyEigenvalues;
        public double psdResidual;
        public double hermiticityResidual;
        public double passivityResidual;
        public double m00;
        public double depolarizationIndex;
    }

    /**
     * Evaluates the physical constraints without modifying the candidate.
     *
     * <p>A Mueller matrix is physically realizable iff its Cloude coherency
     * matrix is Hermitian positive semi-definite. The passive check additionally
     * requires M00 &gt;= |M0j| and Mi0 and transmittance 0 &lt;= M00 &lt;= 1 after
     * normalisation to incident unit irradiance.
     */
    public static PhysicalReport checkPhysical(double[][] m) {
        PhysicalReport rep = new PhysicalReport();
        rep.m00 = m[0][0];
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        coherency(m, re, im);

        double herm = 0.0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                herm = Math.max(herm, Math.abs(re[i][j] - re[j][i]));
                herm = Math.max(herm, Math.abs(im[i][j] + im[j][i]));
            }
        }
        rep.hermiticityResidual = herm;

        double[] eig = LinAlg.eigHermitian(re, im);
        rep.coherencyEigenvalues = eig;
        double neg = 0.0;
        for (double v : eig) {
            if (v < 0) {
                neg = Math.max(neg, -v);
            }
        }
        rep.psdResidual = neg;

        double scale = m[0][0] == 0 ? 1.0 : Math.abs(m[0][0]);
        double pass = 0.0;
        for (int k = 1; k < 4; k++) {
            pass = Math.max(pass, Math.abs(m[0][k]) / scale - 1.0);
            pass = Math.max(pass, Math.abs(m[k][0]) / scale - 1.0);
        }
        if (m[0][0] < 0 || m[0][0] > 1.0 + 1e-9) {
            pass = Math.max(pass, m[0][0] > 1.0 ? m[0][0] - 1.0 : -m[0][0]);
        }
        rep.passivityResidual = Math.max(0.0, pass);
        rep.passive = rep.passivityResidual <= 1e-8;
        rep.physicallyRealizable = neg <= 1e-8 && herm <= 1e-8;

        double sum = 0.0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i != 0 || j != 0) {
                    sum += m[i][j] * m[i][j];
                }
            }
        }
        rep.depolarizationIndex = m[0][0] == 0 ? 0 : Math.sqrt(sum) / Math.abs(m[0][0]);
        return rep;
    }

    /**
     * Row of the measurement design matrix.
     *
     * <p>For analyser Stokes vector a and generator Stokes vector g (both with
     * a0 = 1), I_darkfree = a^T M g, so the row equals kron(g, a) when M is
     * flattened row-major.
     */
    public static double[] designRow(double[] a, double[] g) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                row[4 * i + j] = a[i] * g[j];
            }
        }
        return row;
    }

    /** Stokes vector of a fully polarised state (azimuth deg, ellipticity deg). */
    public static double[] stokesState(double azimuthDeg, double ellipticityDeg) {
        double az = Math.toRadians(azimuthDeg);
        double el = Math.toRadians(ellipticityDeg);
        return new double[]{
                1.0,
                Math.cos(2.0 * el) * Math.cos(2.0 * az),
                Math.cos(2.0 * el) * Math.sin(2.0 * az),
                Math.sin(2.0 * el)
        };
    }

    /** Result of a weighted Mueller fit (generalised least squares). */
    public static final class FitResult {
        public double[][] m = new double[4][4];
        public double[][] cov = new double[16][16];
        public double[][] elementError = new double[4][4];
        public double[] singularValues;
        public double condition;
        public int rank;
        public int nullity;
        public List<double[]> nullspace = new ArrayList<>();
        public boolean unique;
        public double rankTolerance;
        public double chiSquared;
        public int dof;
    }

    /**
     * Solve y = H mVec with covariance C for the measurement vector.
     *
     * <p>The normal equations H^T C^{-1} H are solved by SVD. Near-singular
     * designs are not force-inverted: the null space is returned and the
     * truncated minimum-norm solution is clearly marked non-unique.
     */
    public static FitResult weightedFit(double[][] h, double[] y, double[][] cov,
                                        double rankRelTol) {
        int n = y.length;
        FitResult r = new FitResult();
        r.rankTolerance = rankRelTol;

        double[][] cInv;
        try {
            cInv = LinAlg.inverseSpd(cov);
        } catch (RuntimeException e) {
            cInv = new double[n][n];
            for (int i = 0; i < n; i++) {
                cInv[i][i] = 1.0 / Math.max(cov[i][i], 1e-30);
            }
        }
        double[][] ht = LinAlg.transpose(h);
        double[][] a = LinAlg.multiply(LinAlg.multiply(ht, cInv), h);
        double[] b = LinAlg.multiply(LinAlg.multiply(ht, cInv), y);

        LinAlg.Svd svd = LinAlg.svd(a);
        r.singularValues = svd.s;
        r.condition = svd.condition();
        r.rank = svd.rank(rankRelTol);
        r.nullity = 16 - r.rank;
        r.unique = r.nullity == 0;
        r.nullspace = svd.nullspace(rankRelTol);

        double[] x = new double[16];
        double[][] aPlus = new double[16][16];
        for (int k = 0; k < 16; k++) {
            if (svd.s[k] > rankRelTol * svd.s[0]) {
                double[] vk = new double[16];
                for (int i = 0; i < 16; i++) {
                    vk[i] = svd.vt[k][i];
                }
                double[] uk = new double[16];
                for (int i = 0; i < 16; i++) {
                    uk[i] = svd.u[i][k];
                }
                for (int i = 0; i < 16; i++) {
                    for (int j = 0; j < 16; j++) {
                        aPlus[i][j] += vk[i] * uk[j] / svd.s[k];
                    }
                }
            }
        }
        x = LinAlg.multiply(aPlus, b);
        for (int i = 0; i < 4; i++) {
            System.arraycopy(x, 4 * i, r.m[i], 0, 4);
        }

        // Covariance of the (possibly rank-deficient) estimate: V+ A V+.
        r.cov = LinAlg.multiply(LinAlg.multiply(aPlus, a), aPlus);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double v = r.cov[4 * i + j][4 * i + j];
                r.elementError[i][j] = v > 0 ? Math.sqrt(v) : 0.0;
            }
        }

        double[] yhat = LinAlg.multiply(h, x);
        double chi = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                chi += (y[i] - yhat[i]) * cInv[i][j] * (y[j] - yhat[j]);
            }
        }
        r.chiSquared = chi;
        r.dof = n - r.rank;
        return r;
    }

    /**
     * Applies instrument matrices: measured intensity vector is modelled as
     * a_d^T W_A M W_G g_d (plus dark), where W_A/W_G are the analyser/generator
     * calibration matrices. Returns the corrected generator/analyser states.
     */
    public static void calibrateStates(double[][] wGenerator, double[][] wAnalyser,
                                       List<double[]> generators, List<double[]> analysers,
                                       List<double[]> gOut, List<double[]> aOut) {
        gOut.clear();
        aOut.clear();
        for (double[] g : generators) {
            gOut.add(LinAlg.multiply(wGenerator, g));
        }
        for (double[] a : analysers) {
            aOut.add(LinAlg.multiply(wAnalyser, a));
        }
    }
}
