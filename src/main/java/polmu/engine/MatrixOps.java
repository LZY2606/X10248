package polmu.engine;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition_F64;
import org.ejml.interfaces.decomposition.SingularValueDecomposition_F64;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Linear algebra helpers for Mueller calculus, built on EJML. */
public final class MatrixOps {
    private MatrixOps() {}

    public static DMatrixRMaj mat(double[][] a) {
        return new DMatrixRMaj(a);
    }

    public static double[][] toArray(DMatrixRMaj m) {
        double[][] out = new double[m.numRows][m.numCols];
        for (int i = 0; i < m.numRows; i++)
            for (int j = 0; j < m.numCols; j++) out[i][j] = m.get(i, j);
        return out;
    }

    public static DMatrixRMaj kron(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows * b.numRows, a.numCols * b.numCols);
        CommonOps_DDRM.kron(a, b, out);
        return out;
    }

    public static SingularValueDecomposition_F64<DMatrixRMaj> svd(DMatrixRMaj m) {
        SingularValueDecomposition_F64<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(m.numRows, m.numCols, true, true, false);
        if (!svd.decompose(m.copy())) throw new IllegalStateException("SVD failed");
        return svd;
    }

    public static double conditionNumber(DMatrixRMaj m) {
        SingularValueDecomposition_F64<DMatrixRMaj> svd = svd(m);
        double max = 0, min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < svd.numberOfSingularValues(); i++) {
            double s = svd.getSingularValues()[i];
            max = Math.max(max, s);
            if (s > 0) min = Math.min(min, s);
        }
        if (min == Double.POSITIVE_INFINITY || min == 0) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    /** Moore-Penrose pseudo-inverse via SVD with the given singular-value tolerance. */
    public static DMatrixRMaj pinv(DMatrixRMaj m, double tol) {
        SingularValueDecomposition_F64<DMatrixRMaj> svd = svd(m);
        DMatrixRMaj u = svd.getU(null, false);
        DMatrixRMaj v = svd.getV(null, false);
        double[] s = svd.getSingularValues();
        DMatrixRMaj sp = new DMatrixRMaj(v.numCols, u.numCols);
        for (int i = 0; i < s.length; i++) if (s[i] > tol) sp.set(i, i, 1.0 / s[i]);
        DMatrixRMaj out = new DMatrixRMaj(v.numCols, u.numCols);
        DMatrixRMaj tmp = new DMatrixRMaj(v.numCols, sp.numCols);
        CommonOps_DDRM.mult(v, sp, tmp);
        CommonOps_DDRM.multTransB(tmp, u, out);
        return out;
    }

    /** Orthonormal basis (columns) for the null space of m above tolerance. */
    public static List<double[]> nullSpace(DMatrixRMaj m, double tol) {
        SingularValueDecomposition_F64<DMatrixRMaj> svd = svd(m);
        DMatrixRMaj v = svd.getV(null, false);
        double[] s = svd.getSingularValues();
        List<double[]> basis = new ArrayList<>();
        for (int i = 0; i < s.length; i++) {
            if (s[i] <= tol) {
                double[] col = new double[v.numRows];
                for (int r = 0; r < v.numRows; r++) col[r] = v.get(r, i);
                basis.add(col);
            }
        }
        for (int i = s.length; i < m.numCols; i++) {
            double[] col = new double[m.numCols];
            col[i] = 1.0;
            basis.add(col);
        }
        return basis;
    }

    /** Eigenpairs of a real symmetric matrix, sorted by descending eigenvalue. */
    public static List<EigenPair> eigSymmetric(DMatrixRMaj m) {
        EigenDecomposition_F64<DMatrixRMaj> eig = new SymmetricQRAlgorithmDecomposition<>(true);
        if (!eig.decompose(m.copy())) throw new IllegalStateException("eig failed");
        List<EigenPair> out = new ArrayList<>();
        for (int i = 0; i < eig.getNumberOfEigenvalues(); i++) {
            double[] vec = new double[m.numRows];
            var v = eig.getEigenVector(i);
            if (v != null) for (int r = 0; r < v.getNumElements(); r++) vec[r] = v.get(r);
            out.add(new EigenPair(eig.getEigenvalue(i).real, vec));
        }
        out.sort(Comparator.comparingDouble((EigenPair p) -> p.value).reversed());
        return out;
    }

    public record EigenPair(double value, double[] vector) {}

    // ---- Pauli algebra over complex 4x4 matrices (re/im pairs) ----

    static final double[][][] PAULI_RE = new double[4][2][2];
    static final double[][][] PAULI_IM = new double[4][2][2];
    static {
        PAULI_RE[0] = new double[][]{{1, 0}, {0, 1}};
        PAULI_RE[1] = new double[][]{{1, 0}, {0, -1}};
        PAULI_RE[2] = new double[][]{{0, 1}, {1, 0}};
        PAULI_IM[3] = new double[][]{{0, -1}, {1, 0}};
    }

    /** P_i tensor conj(P_j) as 4x4 complex (re, im). */
    static void pauliTensor(int i, int j, double[][] re, double[][] im) {
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
            for (int c = 0; c < 2; c++) for (int d = 0; d < 2; d++) {
                double xr = PAULI_RE[i][a][b], xi = PAULI_IM[i][a][b];
                double yr = PAULI_RE[j][c][d], yi = -PAULI_IM[j][c][d]; // conjugate
                re[a * 2 + c][b * 2 + d] = xr * yr - xi * yi;
                im[a * 2 + c][b * 2 + d] = xr * yi + xi * yr;
            }
    }

    /** Coherency matrix H = (1/4) sum_ij M_ij * P_i (x) conj(P_j). Hermitian 4x4. */
    public static void coherency(double[][] m, double[][] hRe, double[][] hIm) {
        double[][] tre = new double[4][4], tim = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            pauliTensor(i, j, tre, tim);
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) {
                hRe[a][b] += 0.25 * m[i][j] * tre[a][b];
                hIm[a][b] += 0.25 * m[i][j] * tim[a][b];
            }
        }
    }

    /** Invert the coherency transform: M_kl = Tr(H * (P_k (x) conj(P_l))). */
    public static double[][] muellerFromCoherency(double[][] hRe, double[][] hIm) {
        double[][] m = new double[4][4];
        double[][] tre = new double[4][4], tim = new double[4][4];
        for (int k = 0; k < 4; k++) for (int l = 0; l < 4; l++) {
            pauliTensor(k, l, tre, tim);
            double sum = 0;
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
                sum += hRe[a][b] * tre[b][a] - hIm[a][b] * tim[b][a];
            m[k][l] = sum;
        }
        return m;
    }

    /** Eigenpairs of a Hermitian 4x4 (re/im) via the real symmetric 8x8 embedding. */
    public static List<HermitianEigenPair> eigHermitian(double[][] hRe, double[][] hIm) {
        DMatrixRMaj a = new DMatrixRMaj(8, 8);
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            a.set(i, j, hRe[i][j]);
            a.set(i, j + 4, -hIm[i][j]);
            a.set(i + 4, j, hIm[i][j]);
            a.set(i + 4, j + 4, hRe[i][j]);
        }
        List<EigenPair> pairs = eigSymmetric(a);
        List<HermitianEigenPair> out = new ArrayList<>();
        for (int k = 0; k < pairs.size(); k += 2) {
            EigenPair p = pairs.get(k);
            double[] re = new double[4], im = new double[4];
            for (int i = 0; i < 4; i++) { re[i] = p.vector[i]; im[i] = p.vector[i + 4]; }
            out.add(new HermitianEigenPair(p.value, re, im));
        }
        return out;
    }

    public record HermitianEigenPair(double value, double[] vecRe, double[] vecIm) {}

    /** Smallest eigenvalue of the coherency matrix of m: negative means non-physical. */
    public static double physicalResidual(double[][] m) {
        double[][] hRe = new double[4][4], hIm = new double[4][4];
        coherency(m, hRe, hIm);
        List<HermitianEigenPair> pairs = eigHermitian(hRe, hIm);
        return pairs.get(pairs.size() - 1).value;
    }

    /**
     * Cloude spectral decomposition candidates: for each rank r = 1..4, the
     * Mueller matrix reconstructed from the r leading coherency eigenpairs.
     */
    public static List<double[][]> cloudeCandidates(double[][] m) {
        double[][] hRe = new double[4][4], hIm = new double[4][4];
        coherency(m, hRe, hIm);
        List<HermitianEigenPair> pairs = eigHermitian(hRe, hIm);
        List<double[][]> out = new ArrayList<>();
        double[][] accRe = new double[4][4], accIm = new double[4][4];
        for (int r = 0; r < 4; r++) {
            HermitianEigenPair p = pairs.get(r);
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) {
                double hr = p.vecRe[a], hi = p.vecIm[a];
                double kr = p.vecRe[b], ki = p.vecIm[b];
                accRe[a][b] += p.value * (hr * kr + hi * ki);
                accIm[a][b] += p.value * (hi * kr - hr * ki);
            }
            out.add(muellerFromCoherency(accRe, accIm));
        }
        return out;
    }

    public static double[] cloudeWeights(double[][] m) {
        double[][] hRe = new double[4][4], hIm = new double[4][4];
        coherency(m, hRe, hIm);
        List<HermitianEigenPair> pairs = eigHermitian(hRe, hIm);
        double[] w = new double[4];
        for (int i = 0; i < 4; i++) w[i] = pairs.get(i).value;
        return w;
    }
}
