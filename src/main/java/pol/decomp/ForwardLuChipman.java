package pol.decomp;

import java.util.ArrayList;
import java.util.List;

import org.ejml.data.DMatrixRMaj;

import pol.domain.Mtx;
import pol.domain.MuellerBlocks;

/**
 * Forward Lu-Chipman polar decomposition: M = M_Delta M_R M_D.
 *
 * Diattenuator from the first row; retarder/depolarizer split by a polar
 * decomposition of the 3x3 block after removing diattenuation.
 */
public final class ForwardLuChipman {
    private ForwardLuChipman() {
    }

    public static Decomposition decompose(DMatrixRMaj m) {
        List<String> notes = new ArrayList<>();
        double[] d = new double[] {m.get(0, 1), m.get(0, 2), m.get(0, 3)};
        double dMag = norm(d);
        DMatrixRMaj md = MuellerBlocks.diattenuator(d);
        DMatrixRMaj mdInv = invertDiattenuator(d, dMag);
        DMatrixRMaj m1 = Mtx.multiply(m, mdInv);

        double[] p = new double[] {m1.get(1, 0), m1.get(2, 0), m1.get(3, 0)};
        DMatrixRMaj t = block3(m1);
        Mtx.Polar polar = Mtx.polarDecomposition(t);
        DMatrixRMaj mDelta = Mtx.identity(4);
        for (int i = 0; i < 3; i++) {
            mDelta.set(i + 1, 0, p[i]);
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

        DMatrixRMaj reconstructed = Mtx.multiply(Mtx.multiply(mDelta, mr4), md);
        double residual = relativeResidual(m, reconstructed);
        boolean valid = Double.isFinite(residual) && residual < 1e-6;

        List<Decomposition.Part> parts = List.of(
                new Decomposition.Part("depolarizer", "M\u0394", mDelta),
                new Decomposition.Part("retarder", "M_R", mr4),
                new Decomposition.Part("diattenuator", "M_D", md));
        List<Decomposition.Metric> metrics = new ArrayList<>();
        metrics.add(new Decomposition.Metric("diattenuation", dMag, "1"));
        metrics.add(new Decomposition.Metric("polarizance", norm(p), "1"));
        metrics.add(new Decomposition.Metric("retardance", retardance(polar.orthogonal()), "rad"));
        double depol = depolarizationIndex(polar.symmetric());
        metrics.add(new Decomposition.Metric("depolarization_index", depol, "1"));
        return new Decomposition("lu_chipman_forward", "Lu-Chipman forward",
                "M = M\u0394 M_R M_D; right-handed retarder, Stokes (I,Q,U,V)", m,
                reconstructed, residual, parts, metrics, valid, String.join("; ", notes));
    }

    private static DMatrixRMaj block3(DMatrixRMaj m) {
        DMatrixRMaj t = Mtx.zeros(3, 3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                t.set(i, j, m.get(i + 1, j + 1));
            }
        }
        return t;
    }

    private static DMatrixRMaj invertDiattenuator(double[] d, double dMag) {
        if (dMag < 1e-12) {
            return Mtx.identity(4);
        }
        DMatrixRMaj md = MuellerBlocks.diattenuator(d);
        DMatrixRMaj inv = Mtx.zeros(4, 4);
        org.ejml.dense.row.CommonOps_DDRM.invert(md, inv);
        return inv;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double relativeResidual(DMatrixRMaj m, DMatrixRMaj rec) {
        return Mtx.normF(Mtx.subtract(m, rec)) / Math.max(Mtx.normF(m), 1e-30);
    }

    static double retardance(DMatrixRMaj r3) {
        double tr = r3.get(0, 0) + r3.get(1, 1) + r3.get(2, 2);
        double cos = Math.max(-1.0, Math.min(1.0, (tr - 1.0) / 2.0));
        return Math.acos(cos);
    }

    static double depolarizationIndex(DMatrixRMaj h3) {
        double tr = h3.get(0, 0) + h3.get(1, 1) + h3.get(2, 2);
        return 1.0 - tr / 3.0;
    }
}
