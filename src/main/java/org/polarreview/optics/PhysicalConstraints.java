package org.polarreview.optics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Physical-realizability checks for a candidate Mueller matrix.
 *
 * <p>Hard rule: violations are reported on the candidate and never repaired by
 * projecting it into the admissible cone and then presenting the projection as
 * the original solution. The raw candidate stays the solution; a separately
 * labeled projection may be offered as one more candidate.
 */
public final class PhysicalConstraints {

    public static final class Report {
        public final boolean physical;
        public final double[] cloudeEigenvalues;
        public final double minEigenvalue;
        public final double minPassiveOutput;
        public final double maxPassiveOutput;
        public final ObjectNode json;

        Report(boolean physical, double[] ev, double minEv, double minOut, double maxOut,
               ObjectNode json) {
            this.physical = physical;
            this.cloudeEigenvalues = ev;
            this.minEigenvalue = minEv;
            this.minPassiveOutput = minOut;
            this.maxPassiveOutput = maxOut;
            this.json = json;
        }
    }

    private static final ObjectMapper M = new ObjectMapper();

    private PhysicalConstraints() {}

    public static Report check(double[][] m) {
        double[][][] h = Cloude.coherency(m);
        HermitianJacobi ej = HermitianJacobi.decompose(h[0], h[1]);
        double[] ev = ej.eigenvalues;
        double minEv = ev[0];
        double traceH = 0;
        for (double e : ev) traceH += e;
        double eigTol = 1e-9 * Math.max(1.0, Math.abs(traceH));
        boolean psd = minEv >= -eigTol;

        // Passivity: output degree of polarization <= 1 for every pure input.
        // Equivalent bounds on outgoing intensity/DoP across input Poincare sphere;
        // evaluate on a dense direction grid plus S0 axis.
        double minOut = Double.POSITIVE_INFINITY;
        double maxOut = Double.NEGATIVE_INFINITY;
        double maxDop = 0;
        // enumerate pure input directions on the sphere via a fibonacci lattice
        int N = 258;
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < N; i++) {
            double z = 1.0 - 2.0 * (i + 0.5) / N;
            double r = Math.sqrt(Math.max(0, 1 - z * z));
            double phi = golden * i;
            double[] sin = {1, r * Math.cos(phi), r * Math.sin(phi), z};
            double[] out = apply(m, sin);
            if (out[0] < minOut) minOut = out[0];
            if (out[0] > maxOut) maxOut = out[0];
            double dop = Math.hypot(Math.hypot(out[1], out[2]), out[3])
                    / Math.max(Math.abs(out[0]), 1e-300);
            if (dop > maxDop) maxDop = dop;
        }
        boolean passive = minOut >= -1e-7 && maxOut <= 1 + 1e-7;
        boolean dopOk = maxDop <= 1 + 1e-7;

        ObjectNode json = M.createObjectNode();
        ArrayNode evn = json.putArray("cloudeEigenvalues");
        for (double e : ev) evn.add(round(e));
        ArrayNode violations = json.putArray("violations");
        if (!psd) {
            ObjectNode v = violations.addObject();
            v.put("rule", "cloude_psd");
            v.put("severity", "hard");
            v.put("residual", round(Math.min(0, minEv)));
            v.put("message",
                    "Cloude coherency matrix is not positive semidefinite; the candidate is not a physical depolarizing Mueller matrix");
        }
        if (!passive) {
            ObjectNode v = violations.addObject();
            v.put("rule", "passive_transmittance");
            v.put("severity", "hard");
            v.put("residual", round(Math.max(-minOut, maxOut - 1.0)));
            v.put("message", "normalized output intensity leaves [0,1] for some pure input");
        }
        if (!dopOk) {
            ObjectNode v = violations.addObject();
            v.put("rule", "output_dop_bounded");
            v.put("severity", "hard");
            v.put("residual", round(maxDop - 1.0));
            v.put("message", "output degree of polarization exceeds 1 for some pure input");
        }
        boolean physical = psd && passive && dopOk;
        json.put("physical", physical);
        json.put("minEigenvalue", round(minEv));
        json.put("depolarizationIndex", round(depolarizationIndex(m)));
        json.put("entropy", round(entropy(ev)));
        json.put("maxOutputDop", round(maxDop));
        return new Report(physical, ev, minEv, minOut, maxOut, json);
    }

    public static double[] apply(double[][] m, double[] s) {
        double[] o = new double[4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) o[i] += m[i][j] * s[j];
        return o;
    }

    public static double depolarizationIndex(double[][] m) {
        double num = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) num += m[i][j] * m[i][j];
        double pi = Math.sqrt(num) / Math.sqrt(3) / Math.abs(m[0][0] == 0 ? 1 : m[0][0]);
        return Math.min(1, pi);
    }

    public static double entropy(double[] ev) {
        double sum = 0;
        for (double e : ev) sum += Math.max(0, e);
        if (sum <= 0) return 0;
        double h = 0;
        for (double e0 : ev) {
            double p = Math.max(0, e0) / sum;
            if (p > 0) h -= p * (Math.log(p) / Math.log(3));
        }
        return h;
    }

    private static double round(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.round(v * 1e12) / 1e12;
    }
}
