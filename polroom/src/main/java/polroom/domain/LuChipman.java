package polroom.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lu-Chipman polar decomposition M = M_Delta M_R M_D (forward) and the reverse
 * decomposition M = M_D M_R M_Delta^R (Ossikovski 2008), carried out on the 3x3
 * characteristic sub-matrix.  Both are retained as candidates; invalid factors
 * are reported rather than silently fixed.
 */
public final class LuChipman {

    public static class Result {
        public final String kind;
        public RealMatrix diattenuator;
        public RealMatrix retarder;
        public RealMatrix depolarizer;
        public double diattenuation;
        public double retardanceRad;
        public double[] retardanceVector = new double[3];
        public double depolarizationPower;
        public double depolarizationIndex;
        public double residualNorm;
        public final List<String> diagnostics = new ArrayList<>();
        public boolean valid = true;

        Result(String kind) {
            this.kind = kind;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("valid", valid);
            m.put("diattenuation", diattenuation);
            m.put("retardanceRad", retardanceRad);
            m.put("retardanceDeg", Math.toDegrees(retardanceRad));
            m.put("retardanceVector", retardanceVector);
            m.put("depolarizationPower", depolarizationPower);
            m.put("depolarizationIndex", depolarizationIndex);
            m.put("residualNorm", residualNorm);
            m.put("diagnostics", diagnostics);
            if (diattenuator != null) {
                m.put("diattenuator", diattenuator.getData());
            }
            if (retarder != null) {
                m.put("retarder", retarder.getData());
            }
            if (depolarizer != null) {
                m.put("depolarizer", depolarizer.getData());
            }
            return m;
        }
    }

    public static Result forward(RealMatrix m) {
        Result r = new Result("forward");
        double m00 = m.getEntry(0, 0);
        double[] d = new double[]{
                m.getEntry(0, 1) / m00,
                m.getEntry(0, 2) / m00,
                m.getEntry(0, 3) / m00
        };
        r.diattenuation = Mat.norm(d);
        if (r.diattenuation > 1.0 + 1e-9) {
            r.valid = false;
            r.diagnostics.add("diattenuation magnitude " + r.diattenuation + " > 1");
        }
        RealMatrix md = diattenuatorMueller(m00, d);
        r.diattenuator = md;

        RealMatrix mdInv;
        try {
            mdInv = Mat.inverse(md);
        } catch (RuntimeException ex) {
            r.valid = false;
            r.diagnostics.add("diattenuator singular: " + ex.getMessage());
            return r;
        }
        RealMatrix mDeltaM = mdInv.multiply(m);
        double[] deltaU = new double[]{
                mDeltaM.getEntry(1, 0),
                mDeltaM.getEntry(2, 0),
                mDeltaM.getEntry(3, 0)
        };
        RealMatrix p = mDeltaM.getSubMatrix(1, 3, 1, 3);

        RealMatrix ppt = p.multiply(p.transpose());
        double epsilon = 1e-12;
        RealMatrix pptReg = ppt.add(Mat.eye(3).scalarMultiply(epsilon));
        RealMatrix mDelta3 = Mat.sqrtSym(pptReg);
        double det = Mat.determinant(mDelta3);
        if (det < 0) {
            r.valid = false;
            r.diagnostics.add("depolarizer sub-matrix has negative determinant");
        }
        RealMatrix mDelta3Inv = Mat.inverse(mDelta3);
        RealMatrix r3 = mDelta3Inv.multiply(p);
        double detR = Mat.determinant(r3);
        if (detR < 0) {
            r.valid = false;
            r.diagnostics.add("retarder sub-matrix has negative determinant (improper rotation)");
        }

        double retardance = Math.acos(clamp((trace3(r3) - 1) / 2.0, -1, 1));
        r.retardanceRad = retardance;
        r.retardanceVector = axisFromRotation(r3, retardance);

        RealMatrix mDelta = assemble(deltaU, mDelta3, 0);
        RealMatrix mR = assemble(new double[3], r3, 0);
        r.depolarizer = mDelta;
        r.retarder = mR;

        double norm3 = norm3(mDelta3);
        r.depolarizationPower = clamp(1 - Math.abs(det) / (norm3 * norm3 * norm3), 0, 1);
        r.depolarizationIndex = PhysicalConstraints.check(m).depolarizationIndex;

        RealMatrix recon = mDelta.multiply(mR).multiply(md);
        r.residualNorm = frob(recon.subtract(m));
        return r;
    }

    public static Result reverse(RealMatrix m) {
        RealMatrix mt = m.transpose();
        Result fwd = forward(mt);
        Result r = new Result("reverse");
        r.valid = fwd.valid;
        r.diagnostics.addAll(fwd.diagnostics);
        r.diattenuator = transpose4(fwd.depolarizer);
        r.retarder = transpose4(fwd.retarder);
        r.depolarizer = transpose4(fwd.diattenuator);
        r.diattenuation = Mat.norm(new double[]{
                m.getEntry(1, 0), m.getEntry(2, 0), m.getEntry(3, 0)
        }) / m.getEntry(0, 0);
        r.retardanceRad = fwd.retardanceRad;
        r.retardanceVector = fwd.retardanceVector.clone();
        r.depolarizationPower = fwd.depolarizationPower;
        r.depolarizationIndex = fwd.depolarizationIndex;
        RealMatrix recon = r.diattenuator.multiply(r.retarder).multiply(r.depolarizer);
        r.residualNorm = frob(recon.subtract(m));
        return r;
    }

    public static List<Result> candidates(RealMatrix m) {
        return List.of(forward(m), reverse(m));
    }

    private static RealMatrix diattenuatorMueller(double m00, double[] d) {
        double dnorm2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
        double sp = Math.sqrt(Math.max(1e-15, 1 - dnorm2));
        double[][] out = new double[4][4];
        out[0][0] = m00;
        for (int j = 0; j < 3; j++) {
            out[0][j + 1] = m00 * d[j];
            out[j + 1][0] = m00 * d[j];
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double delta = i == j ? 1 : 0;
                out[i + 1][j + 1] = m00 * (delta * sp + d[i] * d[j] / (1 + sp));
            }
        }
        return new Array2DRowRealMatrix(out);
    }

    private static RealMatrix assemble(double[] firstCol, RealMatrix sub3, double m00) {
        RealMatrix out = Mat.eye(4).scalarMultiply(m00 == 0 ? 1 : m00);
        if (m00 == 0) {
            out = Mat.eye(4);
        }
        for (int i = 0; i < 3; i++) {
            out.setEntry(i + 1, 0, firstCol[i]);
        }
        out.setSubMatrix(sub3.scalarMultiply(out.getEntry(0, 0)).getData(), 1, 1);
        return out;
    }

    private static double[] axisFromRotation(RealMatrix r3, double retardance) {
        double[] axis = new double[3];
        if (retardance < 1e-12) {
            return axis;
        }
        axis[0] = (r3.getEntry(2, 1) - r3.getEntry(1, 2)) / (2 * Math.sin(retardance));
        axis[1] = (r3.getEntry(0, 2) - r3.getEntry(2, 0)) / (2 * Math.sin(retardance));
        axis[2] = (r3.getEntry(1, 0) - r3.getEntry(0, 1)) / (2 * Math.sin(retardance));
        return axis;
    }

    private static RealMatrix transpose4(RealMatrix m) {
        return m == null ? null : m.transpose();
    }

    private static double trace3(RealMatrix m) {
        return m.getEntry(0, 0) + m.getEntry(1, 1) + m.getEntry(2, 2);
    }

    private static double norm3(RealMatrix m) {
        double s = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                s += m.getEntry(i, j) * m.getEntry(i, j);
            }
        }
        return Math.sqrt(s / 3.0);
    }

    private static double frob(RealMatrix m) {
        return m.getFrobeniusNorm();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private LuChipman() {
    }
}
