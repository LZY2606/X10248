package io.polroom;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mueller decomposition candidates.
 *
 * Multiple legal decompositions of the same estimated matrix are kept side by side;
 * the review UI switches between them and the analyst picks one. Decomposing a
 * non-physical candidate is allowed (diagnostics are shown) but the result is flagged,
 * never silently replaced by a projected matrix.
 */
public final class Decompositions {

    public record Candidate(String name, Map<String, Object> parameters,
                            double reconstructionResidual, boolean physicalInput) {}

    private Decompositions() {}

    public static List<Candidate> all(RealMatrix m) {
        List<Candidate> out = new ArrayList<>();
        out.add(luChipman(m));
        out.add(reversePolar(m));
        out.add(cloudeSum(m));
        return out;
    }

    private static RealMatrix inv(RealMatrix a) {
        return new org.apache.commons.math3.linear.LUDecomposition(a).getSolver().getInverse();
    }

    /** Lu-Chipman (1996): M = M_delta * M_R * M_D, diattenuator then retarder then depolarizer. */
    public static Candidate luChipman(RealMatrix m) {
        double m00 = m.getEntry(0, 0) == 0 ? 1.0 : m.getEntry(0, 0);
        double[] d = {
                m.getEntry(0, 1) / m00, m.getEntry(0, 2) / m00, m.getEntry(0, 3) / m00
        };
        double dnorm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double dClamp = Math.min(dnorm, 0.999999);

        double[][] md = new double[4][4];
        md[0][0] = 1;
        md[0][1] = d[0]; md[0][2] = d[1]; md[0][3] = d[2];
        for (int i = 1; i < 4; i++) md[i][0] = d[i - 1];
        for (int i = 1; i < 4; i++)
            for (int j = 1; j < 4; j++)
                md[i][j] = (i == j ? Math.sqrt(1 - dClamp * dClamp) : 0)
                        + d[i - 1] * d[j - 1] / (1 + Math.sqrt(1 - dClamp * dClamp));
        RealMatrix mdInv = inv(LA.m(md));
        RealMatrix mDelta = m.scalarMultiply(1.0 / m00).multiply(mdInv);

        double[] p = {mDelta.getEntry(1, 0), mDelta.getEntry(2, 0), mDelta.getEntry(3, 0)};
        double pnorm = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        double pClamp = Math.min(pnorm, 0.999999);
        double[] ph = {p[0], p[1], p[2]};

        double[][] mr = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                mr[i][j] = mDelta.getEntry(i + 1, j + 1);
        double trace = mr[0][0] + mr[1][1] + mr[2][2];
        double retardance = Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2.0)));

        double[] axis = {
                mr[1][2] - mr[2][1],
                mr[2][0] - mr[0][2],
                mr[0][1] - mr[1][0]
        };
        double axisNorm = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (axisNorm > 1e-14) for (int i = 0; i < 3; i++) axis[i] /= axisNorm;

        double alpha = 1 - (pClamp * pClamp);
        double[] eigen = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = 0;
            for (int j = 0; j < 3; j++) {
                double mij = mDelta.getEntry(i + 1, j + 1);
                s += mij * mij;
            }
            eigen[i] = Math.sqrt(Math.max(0, s - ph[i] * ph[i]));
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ordering", "M_delta * M_R * M_D");
        params.put("diattenuation", d);
        params.put("diattenuationMagnitude", dnorm);
        params.put("retardance_rad", retardance);
        params.put("retardanceAxis", axis);
        params.put("polarizance", p);
        params.put("depolarizationEigenvalues", eigen);
        params.put("depolarizationPower", alpha);
        params.put("diattenuationClamped", dnorm >= 1.0 - 1e-6);

        RealMatrix recon = LA.m(md);
        double residual = reconstructionResidual(m, candidateProduct(mDelta, retarderFromAxis(axis, retardance), LA.m(md), m00));
        return new Candidate("lu-chipman", params, residual, Physical.assess(m).physicallyRealizable());
    }

    private static RealMatrix retarderFromAxis(double[] axis, double r) {
        double cr = Math.cos(r), sr = Math.sin(r);
        double[][] mr = new double[4][4];
        mr[0][0] = 1;
        for (int i = 1; i < 4; i++) mr[i][0] = mr[0][i] = 0;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                mr[i + 1][j + 1] = (i == j ? cr : 0)
                        + axis[i] * axis[j] * (1 - cr)
                        + (new double[][] {{0, -axis[2], axis[1]}, {axis[2], 0, -axis[0]}, {-axis[1], axis[0], 0}})[i][j] * sr;
        return LA.m(mr);
    }

    private static RealMatrix candidateProduct(RealMatrix mDelta, RealMatrix mR, RealMatrix mD, double m00) {
        double[][] fullDelta = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) fullDelta[i][j] = mDelta.getEntry(i, j);
        RealMatrix delta4 = LA.m(fullDelta);
        return delta4.multiply(mR).multiply(mD).scalarMultiply(m00);
    }

    /** Reverse polar decomposition M = M_U * M_H (Gil-Bernabeu style polar factors). */
    public static Candidate reversePolar(RealMatrix m) {
        RealMatrix mtm = m.transpose().multiply(m);
        var eig = new org.apache.commons.math3.linear.EigenDecomposition(mtm);
        RealMatrix h = MatrixUtils.createRealMatrix(4, 4);
        for (int k = 0; k < 4; k++) {
            double root = Math.sqrt(Math.max(0, eig.getRealEigenvalue(k)));
            RealMatrix vv = eig.getV().getColumnMatrix(k);
            h = h.add(vv.multiply(vv.transpose()).scalarMultiply(root));
        }
        RealMatrix u = m.multiply(inv(h));
        double residual = LA.frobenius(u.multiply(h).subtract(m));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ordering", "M = M_U * M_H (reverse polar)");
        params.put("unitaryFactor", LA.arr(u));
        params.put("hermitianFactor", LA.arr(h));
        params.put("hermitianEigenvalues", sortedEigen(h));
        return new Candidate("reverse-polar", params, residual, Physical.assess(m).physicallyRealizable());
    }

    /** Cloude sum decomposition into up to four pure (rank-1 coherency) components. */
    public static Candidate cloudeSum(RealMatrix m) {
        RealMatrix h = Physical.coherency(m);
        var eig = new org.apache.commons.math3.linear.EigenDecomposition(h);
        double[] weights = new double[4];
        double wsum = 0;
        for (int k = 0; k < 4; k++) {
            weights[k] = Math.max(0, eig.getRealEigenvalue(k));
            wsum += weights[k];
        }
        double entropy = 0;
        for (double w : weights) {
            if (w > 0 && wsum > 0) {
                double p = w / wsum;
                entropy -= p * (Math.log(p) / Math.log(4));
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ordering", "Cloude coherency spectral sum");
        params.put("coherencyEigenvalues", java.util.Arrays.stream(eig.getRealEigenvalues()).boxed().sorted(java.util.Collections.reverseOrder()).toList());
        params.put("purityEntropy", entropy);
        RealMatrix reconstructed = reconstructFromCoherency(eig, weights, wsum);
        double residual = LA.frobenius(reconstructed.subtract(m));
        return new Candidate("cloude-sum", params, residual, Physical.assess(m).physicallyRealizable());
    }

    private static RealMatrix reconstructFromCoherency(org.apache.commons.math3.linear.EigenDecomposition eig,
                                                       double[] weights, double wsum) {
        RealMatrix h = MatrixUtils.createRealMatrix(4, 4);
        for (int k = 0; k < 4; k++) {
            RealMatrix vv = eig.getV().getColumnMatrix(k);
            h = h.add(vv.multiply(vv.transpose()).scalarMultiply(weights[k]));
        }
        RealMatrix rec = MatrixUtils.createRealMatrix(4, 4);
        double[][][] sigma = {Pauli.s0(), Pauli.s1(), Pauli.s2(), Pauli.s3()};
        for (int mu = 0; mu < 4; mu++) {
            for (int nu = 0; nu < 4; nu++) {
                double val = 0;
                for (int a = 0; a < 4; a++)
                    for (int b = 0; b < 4; b++)
                        val += h.getEntry(a, b) * trace(sigma[a], sigma[mu]) * trace(sigma[b], sigma[nu]);
                rec.setEntry(mu, nu, val);
            }
        }
        return rec;
    }

    private static double trace(double[][] a, double[][] b) {
        double t = 0;
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a.length; j++) t += a[i][j] * b[j][i];
        return t;
    }

    private static double[] sortedEigen(RealMatrix h) {
        double[] e = new org.apache.commons.math3.linear.EigenDecomposition(h).getRealEigenvalues();
        java.util.Arrays.sort(e);
        return e;
    }

    private static double reconstructionResidual(RealMatrix original, RealMatrix reconstructed) {
        double f = LA.frobenius(original);
        return LA.frobenius(reconstructed.subtract(original)) / Math.max(1e-30, f);
    }

    public static Map<String, Object> toMap(Candidate c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", c.name());
        out.put("physicalInput", c.physicalInput());
        out.put("reconstructionResidual", c.reconstructionResidual());
        out.put("parameters", c.parameters());
        return out;
    }
}
