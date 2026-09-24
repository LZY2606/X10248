package pol.decomp;

import java.util.ArrayList;
import java.util.List;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

import pol.domain.Mtx;
import pol.domain.MuellerBlocks;

/**
 * Reverse Ossikovski polar decomposition: M = M_D M_R M_Delta.
 *
 * Here diattenuation is carried by the first column (polarizance vector of
 * the reduced matrix), and the 3x3 block polar decomposition is taken on the
 * depolarizer side. Keeping both forward and reverse candidates exposes the
 * ordering ambiguity that a single-button "decompose" view would hide.
 */
public final class ReverseOssikovski {
    private ReverseOssikovski() {
    }

    public static Decomposition decompose(DMatrixRMaj m) {
        List<String> notes = new ArrayList<>();

        double[] p = new double[] {m.get(1, 0), m.get(2, 0), m.get(3, 0)};
        double pMag = norm(p);
        DMatrixRMaj md = MuellerBlocks.diattenuator(p);
        DMatrixRMaj mdInv = Mtx.zeros(4, 4);
        CommonOps_DDRM.invert(md, mdInv);
        DMatrixRMaj m1 = Mtx.multiply(mdInv, m);

        double[] d = new double[] {m1.get(0, 1), m1.get(0, 2), m1.get(0, 3)};
        DMatrixRMaj t = Mtx.zeros(3, 3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                t.set(i, j, m1.get(i + 1, j + 1));
            }
        }
        Mtx.Polar polar = Mtx.polarDecomposition(t);
        DMatrixRMaj mDelta = Mtx.identity(4);
        for (int j = 0; j < 3; j++) {
            mDelta.set(0, j + 1, d[j]);
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mDelta.set(i + 1, j + 1, polar.symmetric().get(i, j));
            }
        }
        DMatrixRMaj mr4 = Mtx.identity(4);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mr4.set(i + 1, j + 1, polar.orthogonal().get(i, j));
            }
        }
        if (polar.reflectionCorrected()) {
            notes.add("polar decomposition required a reflection sign correction (depolarizer block non-physical)");
        }

        DMatrixRMaj reconstructed = Mtx.multiply(Mtx.multiply(md, mr4), mDelta);
        double residual = Mtx.normF(Mtx.subtract(m, reconstructed)) / Math.max(Mtx.normF(m), 1e-30);
        boolean valid = Double.isFinite(residual) && residual < 1e-6;

        List<Decomposition.Part> parts = List.of(
                new Decomposition.Part("diattenuator", "M_D", md),
                new Decomposition.Part("retarder", "M_R", mr4),
                new Decomposition.Part("depolarizer", "M\u0394", mDelta));
        List<Decomposition.Metric> metrics = new ArrayList<>();
        metrics.add(new Decomposition.Metric("diattenuation", pMag, "1"));
        metrics.add(new Decomposition.Metric("polarizance", norm(d), "1"));
        metrics.add(new Decomposition.Metric("retardance", ForwardLuChipman.retardance(polar.orthogonal()), "rad"));
        metrics.add(new Decomposition.Metric("depolarization_index", ForwardLuChipman.depolarizationIndex(polar.symmetric()), "1"));
        return new Decomposition("ossikovski_reverse", "Ossikovski reverse",
                "M = M_D M_R M\u0394; right-handed retarder, Stokes (I,Q,U,V)", m,
                reconstructed, residual, parts, metrics, valid, String.join("; ", notes));
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
