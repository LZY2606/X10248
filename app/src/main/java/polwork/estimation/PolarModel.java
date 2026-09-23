package polwork.estimation;

import org.ejml.data.DMatrixRMaj;
import polwork.domain.Conventions;
import polwork.domain.OpticalState;
import polwork.domain.StateEncoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds calibrated polarization vectors and the flattened 16-column design.
 * Intensity model: I_{g,a} = s_a^T W_a M W_g s_g = (A_g,a \otimes G_g,a) vec(M),
 * with vec order row-major over physical (S0..S3). Conventions (Stokes order,
 * rotation sign) are applied explicitly so they enter the fingerprint.
 */
public final class PolarModel {

    private PolarModel() {}

    /** Physical-order (S0..S3) Stokes emitted by a calibrated generator state. */
    public static double[] generatorVector(OpticalState state, double[][] wGen) {
        return apply(state, wGen);
    }

    /** Physical-order analyzer Stokes (row) for a calibrated analyzer state. */
    public static double[] analyzerVector(OpticalState state, double[][] wAna) {
        return apply(state, wAna);
    }

    private static double[] apply(OpticalState state, double[][] w) {
        double[] nominal = new double[4];
        for (int i = 0; i < 4; i++) nominal[i] = state.stokesPhysical.get(i);
        if (w == null) return nominal;
        DMatrixRMaj wm = Linear.mat(w);
        DMatrixRMaj sv = Linear.vec(nominal);
        return Linear.vecArr(Linear.mult(wm, sv));
    }

    /**
     * Design rows for the Cartesian product. Row k for (g,a): kron(A_row, G).
     * Also returns the (g,a) ordering list and the applied convention tag.
     */
    public static BuiltDesign build(StateEncoding encoding,
                                    double[][] wGen,
                                    double[][] wAna) {
        int ng = encoding.generators.size();
        int na = encoding.analyzers.size();
        int rows = ng * na;
        DMatrixRMaj a = new DMatrixRMaj(rows, 16);
        List<String> ordering = new ArrayList<>();
        Conventions conv = encoding.conventions;
        int[] perm = conv.storagePermutation();

        int r = 0;
        for (int g = 0; g < ng; g++) {
            double[] gphys = generatorVector(encoding.generators.get(g), wGen);
            for (int an = 0; an < na; an++) {
                double[] aphys = analyzerVector(encoding.analyzers.get(an), wAna);
                // reorder into stored convention before forming the row
                double[] gst = reorder(gphys, perm);
                double[] ast = reorder(aphys, perm);
                // I = a^T M g => coefficient of M_{ij} is a_i g_j; flattening
                // row-major (index i*4+j) needs the outer product a_col (x) g_row.
                DMatrixRMaj acol = Linear.vec(ast);          // 4x1
                DMatrixRMaj growT = new DMatrixRMaj(1, 4);
                for (int j = 0; j < 4; j++) growT.set(0, j, gst[j]);
                DMatrixRMaj row = Linear.kron(acol, growT);  // 4x4, flat 1x16
                for (int c = 0; c < 16; c++) a.set(r, c, row.get(c / 4, c % 4));
                ordering.add(encoding.generators.get(g).name + "x" + encoding.analyzers.get(an).name);
                r++;
            }
        }
        return new BuiltDesign(a, ordering, conv.tag());
    }

    private static double[] reorder(double[] physical, int[] perm) {
        // perm[i] = storage slot of physical component i
        double[] stored = new double[4];
        for (int i = 0; i < 4; i++) stored[perm[i]] = physical[i];
        return stored;
    }

    public record BuiltDesign(DMatrixRMaj matrix, List<String> ordering, String conventionTag) {}
}
