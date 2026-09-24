package pol.decomp;

import java.util.List;

import org.ejml.data.DMatrixRMaj;

import pol.domain.Cplx;
import pol.domain.JacobiEigen;
import pol.domain.Mtx;
import pol.domain.Pauli;
import pol.domain.PhysicalCheck;

/**
 * Cloude spectral decomposition candidate:
 *   Mhat = sum_k lambda_k M_k,
 *   (M_k)_{mu nu} = u_k^dag (sigma_mu \otimes sigma_nu^*) u_k,
 * with lambda_k the coherency eigenvalues.
 *
 * Negative eigenvalues are reported rather than clipped: clipping would be a
 * projection that must not be passed off as the measured candidate.
 */
public final class CloudeSpectral {
    private CloudeSpectral() {
    }

    public static Decomposition decompose(DMatrixRMaj m) {
        double m00 = m.get(0, 0);
        Cplx[][] h = PhysicalCheck.coherencyComplex(m);
        double[] eig = PhysicalCheck.realEigenvaluesOfHermitian(h);
        Cplx[][] u = PhysicalCheck.eigenvectors(h);
        Cplx[][][] sigma = Pauli.complexPauli();

        java.util.List<Decomposition.Part> parts = new java.util.ArrayList<>();
        java.util.List<Decomposition.Metric> metrics = new java.util.ArrayList<>();
        DMatrixRMaj reconstructedNorm = Mtx.zeros(4, 4);
        int negativeCount = 0;
        for (int k = 0; k < 4; k++) {
            double lambda = eig[k];
            if (lambda < -1e-9) {
                negativeCount++;
            }
            DMatrixRMaj mk = componentMatrix(sigma, column(u, k));
            parts.add(new Decomposition.Part("cloude_component_" + (k + 1),
                    String.format("\u03bb_%d M_%d", k + 1, k + 1), Mtx.multiply(lambda, mk)));
            reconstructedNorm = Mtx.add(reconstructedNorm, Mtx.multiply(lambda, mk));
            metrics.add(new Decomposition.Metric("lambda_" + (k + 1), lambda, "1"));
        }
        DMatrixRMaj reconstructed = Mtx.multiply(m00, reconstructedNorm);
        double residual = Mtx.normF(Mtx.subtract(m, reconstructed)) / Math.max(Mtx.normF(m), 1e-30);
        boolean valid = Double.isFinite(residual) && residual < 1e-7 && negativeCount == 0;
        String note = negativeCount == 0
                ? "all coherency eigenvalues non-negative"
                : negativeCount + " negative eigenvalue(s): non-physical candidate, eigenvalue(s) reported without clipping";
        return new Decomposition("cloude_spectral", "Cloude spectral",
                "sum decomposition over Pauli/Kronecker basis, Stokes (I,Q,U,V)", m,
                reconstructed, residual, parts, metrics, valid, note);
    }

    private static Cplx[] column(Cplx[][] u, int k) {
        Cplx[] col = new Cplx[4];
        for (int i = 0; i < 4; i++) {
            col[i] = u[i][k];
        }
        return col;
    }

    private static DMatrixRMaj componentMatrix(Cplx[][][] sigma, Cplx[] u) {
        DMatrixRMaj mk = Mtx.zeros(4, 4);
        for (int mu = 0; mu < 4; mu++) {
            for (int nu = 0; nu < 4; nu++) {
                Cplx[][] kmat = kronConj(sigma[mu], sigma[nu]);
                mk.set(mu, nu, expectation(kmat, u));
            }
        }
        return mk;
    }

    private static double expectation(Cplx[][] k, Cplx[] u) {
        Cplx total = Cplx.ZERO;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                total = Cplx.add(total, Cplx.mul(u[i].conj(), Cplx.mul(k[i][j], u[j])));
            }
        }
        return total.re();
    }

    private static Cplx[][] kronConj(Cplx[][] a, Cplx[][] b) {
        Cplx[][] out = new Cplx[4][4];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int u = 0; u < 2; u++) {
                    for (int v = 0; v < 2; v++) {
                        out[2 * i + u][2 * j + v] = Cplx.mul(a[i][j], b[u][v].conj());
                    }
                }
            }
        }
        return out;
    }
}
