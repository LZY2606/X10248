package polarmueller.core;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

import java.util.ArrayList;
import java.util.List;

/**
 * Polar-decomposition candidates. Multiple legal candidates are always kept;
 * a non-physical input is flagged and never silently projected first.
 */
public final class Decomposition {
    public static final String RULE_VERSION = "polar-decomp/v1";

    private Decomposition() {}

    public record Candidate(
            String name,            // lu-chipman | reverse-order
            String status,          // ok | flagged_nonphysical_input
            double[][] diattenuator,
            double[][] retarder,
            double[][] depolarizer,
            double diattenuation,
            double residualNorm,    // ||M - Mdep*Mret*Mdia||_F
            List<String> diagnostics
    ) {}

    public static List<Candidate> decompose(double[] m16, boolean inputNonPhysical) {
        List<Candidate> out = new ArrayList<>();
        out.add(one("lu-chipman", m16, inputNonPhysical, false));
        out.add(one("reverse-order", m16, inputNonPhysical, true));
        return out;
    }

    private static Candidate one(String name, double[] m16, boolean flagged, boolean reverse) {
        List<String> diag = new ArrayList<>();
        if (flagged) diag.add("input Mueller candidate is non-physical; decomposition shown for inspection only, not projected");
        DMatrixRMaj m = new DMatrixRMaj(4, 4);
        for (int i = 0; i < 16; i++) m.set(i / 4, i % 4, m16[i]);
        double m00 = m.get(0, 0);
        double[] d = {m.get(0, 1) / m00, m.get(0, 2) / m00, m.get(0, 3) / m00};
        double dNorm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);

        // Diattenuator M_D
        DMatrixRMaj md = diattenuator(d, dNorm);
        DMatrixRMaj mdInv = MatrixOps.invert(md);
        DMatrixRMaj mp = new DMatrixRMaj(4, 4);
        CommonOps_DDRM.mult(m, mdInv, mp);

        // 3x3 submatrix polar factor
        DMatrixRMaj sub = new DMatrixRMaj(3, 3);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) sub.set(i, j, mp.get(i + 1, j + 1));
        DMatrixRMaj sym = new DMatrixRMaj(3, 3);
        if (!reverse) {
            DMatrixRMaj sst = new DMatrixRMaj(3, 3);
            CommonOps_DDRM.multTransB(sub, sub, sst);   // sub * sub^T
            sym = sst;
        } else {
            DMatrixRMaj sts = new DMatrixRMaj(3, 3);
            CommonOps_DDRM.multTransA(sub, sub, sts);   // sub^T * sub
            sym = sts;
        }
        DMatrixRMaj mDelta = MatrixOps.sqrtmSymmetric(sym);
        DMatrixRMaj mDeltaInv = MatrixOps.pinv(mDelta, 1e-12);
        DMatrixRMaj mRet = new DMatrixRMaj(3, 3);
        if (!reverse) CommonOps_DDRM.mult(mDeltaInv, sub, mRet);
        else CommonOps_DDRM.mult(sub, mDeltaInv, mRet);

        double[][] dep = embed(mDelta, mp);
        double[][] ret = embedRot(mRet);
        double[][] dia = MatrixOps.toArray(md);

        // residual of the full product
        DMatrixRMaj prod = MatrixOps.mat(dep);
        DMatrixRMaj tmp = new DMatrixRMaj(4, 4);
        CommonOps_DDRM.mult(prod, MatrixOps.mat(ret), tmp);
        DMatrixRMaj full = new DMatrixRMaj(4, 4);
        CommonOps_DDRM.mult(tmp, MatrixOps.mat(dia), full);
        CommonOps_DDRM.subtract(full, m, full);
        double residual = normF(full);

        String status = flagged ? "flagged_nonphysical_input" : "ok";
        return new Candidate(name, status, dia, ret, dep, dNorm, residual, diag);
    }

    private static DMatrixRMaj diattenuator(double[] d, double dNorm) {
        DMatrixRMaj md = new DMatrixRMaj(4, 4);
        md.set(0, 0, 1);
        double a = Math.sqrt(Math.max(1 - dNorm * dNorm, 0));
        for (int i = 0; i < 3; i++) {
            md.set(0, i + 1, d[i]);
            md.set(i + 1, 0, d[i]);
        }
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double bij = (dNorm > 1e-12) ? (1 - a) * d[i] * d[j] / (dNorm * dNorm) : 0;
                md.set(i + 1, j + 1, a * (i == j ? 1 : 0) + bij);
            }
        return md;
    }

    private static double[][] embed(DMatrixRMaj sub, DMatrixRMaj mp) {
        double[][] out = new double[4][4];
        out[0][0] = 1;
        for (int i = 0; i < 3; i++) out[i + 1][0] = mp.get(i + 1, 0); // P_delta
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) out[i + 1][j + 1] = sub.get(i, j);
        return out;
    }

    private static double[][] embedRot(DMatrixRMaj rot) {
        double[][] out = new double[4][4];
        out[0][0] = 1;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) out[i + 1][j + 1] = rot.get(i, j);
        return out;
    }

    private static double normF(DMatrixRMaj m) {
        double s = 0;
        for (int i = 0; i < m.numRows; i++)
            for (int j = 0; j < m.numCols; j++) s += m.get(i, j) * m.get(i, j);
        return Math.sqrt(s);
    }
}
