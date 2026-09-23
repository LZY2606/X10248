package pmr.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ejml.data.Complex_F64;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition_F64;
import pmr.Json;

import java.util.Locale;

/**
 * Multiple decomposition candidates are retained side by side; switching between
 * them never mutates the measured matrix.
 *  - polar-svd:      M = R H  (unitary x hermitian)
 *  - lu-chipman:     M = M_Delta M_R M_D (forward-product normal form)
 *  - spectral-log:   eigenvalue spectrum and principal log spectrum
 */
public final class Decompositions {
    private Decompositions() {}

    public static ObjectNode polarSvd(double[][] m) {
        DMatrixRMaj a = Linalg.mat(m);
        var d = DecompositionFactory_DDRM.svd(true, true, false);
        d.decompose(a.copy());
        DMatrixRMaj u = d.getU(null, false);
        DMatrixRMaj v = d.getV(null, false);
        double[] sv = new double[4];
        for (int i = 0; i < 4; i++) {
            sv[i] = d.getSingularValue(i);
        }
        DMatrixRMaj sigma = DMatrixRMaj.diag(sv);
        DMatrixRMaj vt = Linalg.transpose(v);
        DMatrixRMaj h = Linalg.mult(Linalg.mult(v, sigma), vt);
        DMatrixRMaj r = Linalg.mult(u, vt);
        ObjectNode n = Json.M.createObjectNode();
        n.put("decomposition", "polar-svd");
        n.put("equation", "M = R_unitary * H_hermitian");
        n.set("unitaryR", matrix(Linalg.arrays(r), "dimensionless"));
        n.set("hermitianH", matrix(Linalg.arrays(h), "dimensionless"));
        return n;
    }

    public static ObjectNode luChipman(double[][] m) {
        double m00 = m[0][0];
        double[] d = new double[3];
        for (int k = 0; k < 3; k++) {
            d[k] = m[0][k + 1] / m00;
        }
        double dLen = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double dLenClamped = Math.min(dLen, 0.999999);
        double[][] md = diattenuator(m00, d, dLenClamped);
        DMatrixRMaj mm = Linalg.mat(m);
        DMatrixRMaj mdInv = Linalg.invert(Linalg.mat(md));
        DMatrixRMaj mp = Linalg.mult(mm, mdInv);
        var svdD = DecompositionFactory_DDRM.svd(true, true, false);
        svdD.decompose(mp.copy());
        DMatrixRMaj u = svdD.getU(null, false);
        DMatrixRMaj v = svdD.getV(null, false);
        DMatrixRMaj vt = Linalg.transpose(v);
        double[] sv = new double[4];
        for (int i = 0; i < 4; i++) {
            sv[i] = svdD.getSingularValue(i);
        }
        DMatrixRMaj mr = Linalg.mult(u, vt);
        DMatrixRMaj mdDelta = Linalg.mult(mp, Linalg.invert(mr));
        double tr = Linalg.trace(mr);
        double cosDelta = Math.max(-1, Math.min(1, (tr - 1) / 2));
        double retardance = Math.acos(cosDelta);
        double depolarizationIndex = depolarizationIndex(mdDelta);
        ObjectNode n = Json.M.createObjectNode();
        n.put("decomposition", "lu-chipman");
        n.put("equation", "M = M_delta * M_R * M_D");
        n.put("diattenuation", dLen);
        n.put("diattenuationUnit", "dimensionless");
        n.put("retardance", retardance);
        n.put("retardanceUnit", "rad");
        n.put("depolarizationIndex", depolarizationIndex);
        n.put("depolarizationIndexUnit", "dimensionless");
        n.set("diattenuatorMD", matrix(md, "dimensionless"));
        n.set("retarderMR", matrix(Linalg.arrays(mr), "dimensionless"));
        n.set("depolarizerMDelta", matrix(Linalg.arrays(mdDelta), "dimensionless"));
        return n;
    }

    public static ObjectNode spectralLog(double[][] m) {
        DMatrixRMaj a = Linalg.mat(m);
        @SuppressWarnings("unchecked")
        EigenDecomposition_F64<DMatrixRMaj> d =
                (EigenDecomposition_F64<DMatrixRMaj>) (EigenDecomposition<?>)
                        DecompositionFactory_DDRM.eig(4, true);
        d.decompose(a.copy());
        ArrayNode eig = Json.M.createArrayNode();
        for (int i = 0; i < 4; i++) {
            Complex_F64 z = d.getEigenvalue(i);
            ObjectNode e = Json.M.createObjectNode();
            e.put("real", z.real);
            e.put("imag", z.imaginary);
            double mod = Math.hypot(z.real, z.imaginary);
            e.put("abs", mod);
            e.put("logAbs", mod > 0 ? Math.log(mod) : null);
            e.put("phase", z.real == 0 && z.imaginary == 0 ? null : Math.atan2(z.imaginary, z.real));
            e.put("phaseUnit", "rad");
            eig.add(e);
        }
        ObjectNode n = Json.M.createObjectNode();
        n.put("decomposition", "spectral-log");
        n.put("equation", "spectrum(lambda) and principal log spectrum");
        n.set("eigenvalues", eig);
        n.put("note", "negative or complex eigenvalues indicate a non-passive/non-normal element");
        return n;
    }

    private static double[][] diattenuator(double m00, double[] d, double dLen) {
        double[][] md = new double[4][4];
        double factor = 1.0 / (1.0 + Math.sqrt(Math.max(0, 1 - dLen * dLen)));
        md[0][0] = m00;
        for (int k = 0; k < 3; k++) {
            md[0][k + 1] = m00 * d[k];
            md[k + 1][0] = m00 * d[k];
        }
        for (int k = 0; k < 3; k++) {
            for (int l = 0; l < 3; l++) {
                md[k + 1][l + 1] = m00 * (k == l ? Math.sqrt(Math.max(0, 1 - dLen * dLen)) : 0)
                        + m00 * factor * d[k] * d[l];
            }
        }
        return md;
    }

    private static double depolarizationIndex(DMatrixRMaj mdDelta) {
        double sumSq = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i != 0 || j != 0) {
                    double v = mdDelta.get(i, j);
                    sumSq += v * v;
                }
            }
        }
        double a00 = mdDelta.get(0, 0);
        if (a00 == 0) {
            return Double.NaN;
        }
        return Math.sqrt(Math.max(0, sumSq / (3 * a00 * a00)));
    }

    private static ObjectNode matrix(double[][] m, String unit) {
        ObjectNode n = Json.M.createObjectNode();
        n.put("unit", unit);
        ArrayNode rows = Json.M.createArrayNode();
        for (double[] row : m) {
            ArrayNode r = Json.M.createArrayNode();
            for (double v : row) {
                r.add(Double.parseDouble(String.format(Locale.ROOT, "%.10g", v)));
            }
            rows.add(r);
        }
        n.set("values", rows);
        return n;
    }
}
