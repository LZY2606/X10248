package pol.domain;

import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller decomposition candidates. Multiple legal candidates are retained
 * because a Mueller matrix does not have a unique physical interpretation:
 *  - forward Lu-Chipman: M = M_delta M_R M_D
 *  - reverse (Ossikovski-style via transpose): M = M_D M_R^T M_delta^T
 *  - Cloude spectral: coherency eigenvalues/entropy
 */
public final class Decomp {

    public static final class Component {
        public final String name;
        public final double[][] matrix;
        public final String parameterSummary;

        Component(String name, double[][] matrix, String parameterSummary) {
            this.name = name;
            this.matrix = matrix;
            this.parameterSummary = parameterSummary;
        }
    }

    public static final class Candidate {
        public final String kind;
        public final List<Component> components;
        public final double reconstructionResidual;
        public final String note;

        Candidate(String kind, List<Component> components, double residual, String note) {
            this.kind = kind;
            this.components = components;
            this.reconstructionResidual = residual;
            this.note = note;
        }
    }

    private Decomp() {
    }

    public static List<Candidate> all(double[][] m) {
        List<Candidate> out = new ArrayList<>();
        out.add(luChipman(m, false));
        out.add(luChipman(m, true));
        out.add(cloudeSpectral(m));
        return out;
    }

    /** Forward or reverse Lu-Chipman. Reverse obtained from the transpose. */
    public static Candidate luChipman(double[][] mArr, boolean reverse) {
        SimpleMatrix m = new SimpleMatrix(mArr);
        if (reverse) {
            m = m.transpose();
        }
        double m00 = m.get(0, 0);
        SimpleMatrix dVec = new SimpleMatrix(3, 1);
        double d = 0;
        for (int i = 0; i < 3; i++) {
            dVec.set(i, m.get(0, i + 1) / (m00 + 1e-300));
            d += dVec.get(i) * dVec.get(i);
        }
        d = Math.sqrt(d);
        SimpleMatrix dHat = dVec.scale(1.0 / (d + 1e-300));
        double t = Math.sqrt(Math.max(0, 1 - d * d));

        SimpleMatrix md = new SimpleMatrix(4, 4);
        md.set(0, 0, 1);
        for (int i = 0; i < 3; i++) {
            md.set(0, i + 1, dVec.get(i));
            md.set(i + 1, 0, dVec.get(i));
            for (int j = 0; j < 3; j++) {
                double dd = (1 - t * t) * dHat.get(i) * dHat.get(j);
                md.set(i + 1, j + 1, (i == j ? t * t : 0) + dd);
            }
        }

        SimpleMatrix mdInv = Linalg.invert(md);
        SimpleMatrix mp = m.mult(mdInv);

        SimpleMatrix pVec = new SimpleMatrix(3, 1);
        double pNorm = 0;
        for (int i = 0; i < 3; i++) {
            pVec.set(i, mp.get(i + 1, 0));
            pNorm += pVec.get(i) * pVec.get(i);
        }
        pNorm = Math.sqrt(pNorm);
        double delta = Math.sqrt(Math.max(0, 1 - pNorm * pNorm));

        SimpleMatrix mr3 = new SimpleMatrix(3, 3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mr3.set(i, j, mp.get(i + 1, j + 1));
            }
        }
        SimpleMatrix j3 = Linalg.sqrtm(mr3.transpose().mult(mr3));
        SimpleMatrix j3Inv = Linalg.inverseSqrtGeneral(mr3.transpose().mult(mr3));
        SimpleMatrix o3 = mr3.mult(j3Inv);

        SimpleMatrix mDelta3 = j3;
        SimpleMatrix mDelta = new SimpleMatrix(4, 4);
        mDelta.set(0, 0, 1);
        for (int i = 0; i < 3; i++) {
            mDelta.set(i + 1, 0, pVec.get(i));
            for (int j = 0; j < 3; j++) {
                mDelta.set(i + 1, j + 1, mDelta3.get(i, j));
            }
        }

        SimpleMatrix mr = new SimpleMatrix(4, 4);
        mr.set(0, 0, 1);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                mr.set(i + 1, j + 1, o3.get(i, j));
            }
        }

        double retardance = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                (o3.trace() - 1) / 2.0))));

        List<Component> comps = new ArrayList<>();
        SimpleMatrix dep = mDelta;
        SimpleMatrix ret = mr;
        SimpleMatrix dia = md;
        if (reverse) {
            dep = mDelta.transpose();
            ret = mr.transpose();
            dia = md.transpose();
        }
        comps.add(new Component(reverse ? "diattenuator_M_D" : "diattenuator_M_D",
                Linalg.toArray(dia), "d=" + Qty.format(d) + " (dimensionless)"));
        comps.add(new Component("retarder_M_R", Linalg.toArray(ret),
                "retardance=" + Qty.format(retardance) + " deg"));
        comps.add(new Component(reverse ? "depolarizer_M_delta^T" : "depolarizer_M_delta",
                Linalg.toArray(dep),
                "|P|=" + Qty.format(pNorm) + ", Delta=" + Qty.format(delta)));

        SimpleMatrix recon = dep.mult(ret).mult(dia);
        double resid = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double e = mArr[i][j] - recon.get(i, j);
                resid += e * e;
            }
        }
        resid = Math.sqrt(resid);
        String note = reverse
                ? "reverse order M = M_D M_R^T M_delta^T (transposed forward decomposition)"
                : "forward order M = M_delta M_R M_D";
        return new Candidate(reverse ? "lu-chipman-reverse" : "lu-chipman-forward",
                comps, resid, note);
    }

    public static Candidate cloudeSpectral(double[][] m) {
        Cloude.Coherency coh = Cloude.analyze(m);
        double[] eig = coh.eigenvalues();
        double sum = 0;
        for (double v : eig) {
            sum += Math.max(v, 0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            double w = sum > 0 ? Math.max(0, eig[i]) / sum : 0;
            sb.append("lambda").append(i).append("=").append(Qty.format(eig[i]))
                    .append(", w").append(i).append("=").append(Qty.format(w));
        }
        sb.append("; entropy=").append(Qty.format(Cloude.entropy(eig))).append(" bits");
        List<Component> comps = new ArrayList<>();
        double[][] eigCol = new double[][] {{eig[0]}, {eig[1]}, {eig[2]}, {eig[3]}};
        comps.add(new Component("coherency_eigenvalues", eigCol, sb.toString()));
        return new Candidate("cloude-spectral", comps, 0,
                "spectral weights only; not a multiplicative Mueller decomposition");
    }
}
