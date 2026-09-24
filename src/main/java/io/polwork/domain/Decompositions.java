package io.polwork.domain;

import io.polwork.linalg.Matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller matrix decomposition candidates.
 *
 * <p>The forward (Lu-Chipman) polar decomposition M = M_Delta M_R M_D is produced
 * together with its reverse-ordering counterpart M = M_D M_R M_Delta, so reviewers
 * always see multiple legal candidates rather than a single silently-chosen answer.
 * For non-physical candidates the factors are labelled FORMAL_ONLY and never passed
 * off as a corrected physical solution.
 */
public final class Decompositions {

    private Decompositions() {
    }

    public record Factor(String name, double[][] matrix, String role, String status,
                         String note) {
    }

    public record Candidate(String id, String label, List<Factor> factors,
                            double residualFrobenius, String physicalStatus,
                            String orderingConvention) {
    }

    public static List<Candidate> candidates(double[][] mData, PhysicalConstraints.PhysicalReport report) {
        List<Candidate> out = new ArrayList<>();
        String physical = report.realizable ? "PHYSICAL" : "FORMAL_ONLY";
        try {
            out.add(forwardLuChipman(mData, physical));
        } catch (RuntimeException e) {
            out.add(degenerateCandidate("LC-FORWARD", "M = MΔ · M_R · M_D (forward)",
                    physical, "forward decomposition undefined: " + e.getMessage()));
        }
        try {
            out.add(reverseLuChipman(mData, physical));
        } catch (RuntimeException e) {
            out.add(degenerateCandidate("LC-REVERSE", "M = M_D · M_R · MΔ (reverse)",
                    physical, "reverse decomposition undefined: " + e.getMessage()));
        }
        return out;
    }

    private static Candidate degenerateCandidate(String id, String label, String physical, String note) {
        List<Factor> factors = new ArrayList<>();
        factors.add(new Factor("UNRESOLVED", null, "factor", "UNDEFINED", note));
        return new Candidate(id, label, factors, Double.NaN, physical, id);
    }

    /** Forward order: M = M_Delta M_R M_D. */
    static Candidate forwardLuChipman(double[][] mData, String physical) {
        Matrix m = Matrix.of(mData);
        double m00 = m.get(0, 0);
        if (Math.abs(m00) < 1e-300) {
            throw new IllegalStateException("m00 too small to normalize");
        }
        Matrix mHat = m.mul(1.0 / m00);

        double[] d = {mHat.get(0, 1), mHat.get(0, 2), mHat.get(0, 3)};
        double dNorm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (dNorm > 1.0 - 1e-12) {
            throw new IllegalStateException("diattenuation at singular boundary |d|=" + dNorm);
        }

        Matrix md = diattenuator(mHat, d);
        Matrix mdInv = md.inverse();
        Matrix mRest = m.mul(mdInv);

        double[] p = {mRest.get(1, 0) / mRest.get(0, 0),
                mRest.get(2, 0) / mRest.get(0, 0),
                mRest.get(3, 0) / mRest.get(0, 0)};
        Matrix mDelta = depolarizer(mRest, p);
        Matrix mDeltaInv = mDelta.inverse();
        Matrix mrData = mDeltaInv.mul(mRest);

        List<Factor> factors = List.of(
                new Factor("M_DELTA", mDelta.toArray(), "depolarizer", physical,
                        "depolarization factor (forward)"),
                new Factor("M_R", mrData.toArray(), "retarder", physical,
                        "homogeneous retarder factor (forward)"),
                new Factor("M_D", md.toArray(), "diattenuator", physical,
                        "diattenuator factor, |d|=" + String.format("%.4g", dNorm))
        );
        double residual = reconstructionResidual(m, factors, true);
        return new Candidate("LC-FORWARD", "M = MΔ · M_R · M_D (forward)", factors,
                residual, physical, "MΔ·MR·MD");
    }

    /** Reverse ordering: operate on M^T which swaps generator/analyzer roles. */
    static Candidate reverseLuChipman(double[][] mData, String physical) {
        double[][] mt = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                mt[j][i] = mData[i][j];
            }
        }
        Candidate onTranspose = forwardLuChipman(mt, physical);
        List<Factor> swapped = new ArrayList<>();
        for (Factor f : onTranspose.factors()) {
            swapped.add(new Factor(f.name(), transpose(f.matrix()), f.role(), f.status(),
                    f.note() + " (transposed for reverse order)"));
        }
        List<Factor> reverseOrder = new ArrayList<>();
        reverseOrder.add(swapped.get(2));
        reverseOrder.add(swapped.get(1));
        reverseOrder.add(swapped.get(0));
        double residual = reconstructionResidual(Matrix.of(mData), reverseOrder, false);
        return new Candidate("LC-REVERSE", "M = M_D · M_R · MΔ (reverse)", reverseOrder,
                residual, physical, "MD·MR·MΔ");
    }

    private static double[][] transpose(double[][] a) {
        if (a == null) {
            return null;
        }
        double[][] t = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[0].length; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }

    private static Matrix diattenuator(Matrix mHat, double[] d) {
        double dNorm2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
        double ad = Math.sqrt(1.0 - dNorm2);
        double[][] md = new double[4][4];
        md[0][0] = 1.0;
        md[0][1] = d[0];
        md[0][2] = d[1];
        md[0][3] = d[2];
        md[1][0] = d[0];
        md[2][0] = d[1];
        md[3][0] = d[2];
        double[][] block = new double[3][3];
        double coefficient = (1.0 - ad) / dNorm2;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                block[i][j] = ad * (i == j ? 1.0 : 0.0) + coefficient * d[i] * d[j];
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                md[i + 1][j + 1] = block[i][j];
            }
        }
        return Matrix.of(md);
    }

    private static Matrix depolarizer(Matrix mRest, double[] p) {
        double mRest00 = mRest.get(0, 0);
        double[][] block = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                block[i][j] = mRest.get(i + 1, j + 1);
            }
        }
        Matrix m3 = Matrix.of(block);
        double pNorm2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2];
        double ap = Math.sqrt(Math.max(1.0 - pNorm2, 0.0));
        double[][] delta = new double[4][4];
        delta[0][0] = 1.0;
        delta[1][0] = p[0];
        delta[2][0] = p[1];
        delta[3][0] = p[2];
        double coefficient = pNorm2 > 1e-24 ? (1.0 - ap) / pNorm2 : 0.0;
        Matrix corrected = m3;
        if (pNorm2 > 1e-24) {
            double[][] pp = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    pp[i][j] = p[i] * p[j];
                }
            }
            Matrix ppM = Matrix.of(pp).mul(m3);
            corrected = m3.sub(ppM.mul(coefficient)).mul(1.0 / ap);
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                delta[i + 1][j + 1] = corrected.get(i, j);
            }
        }
        return Matrix.of(delta).mul(mRest00);
    }

    private static double reconstructionResidual(Matrix original, List<Factor> factors, boolean forward) {
        Matrix product = Matrix.identity(4);
        if (forward) {
            for (int i = factors.size() - 1; i >= 0; i--) {
                product = Matrix.of(factors.get(i).matrix()).mul(product);
            }
        } else {
            for (Factor f : factors) {
                product = Matrix.of(f.matrix()).mul(product);
            }
        }
        return original.sub(product).frobeniusNorm()
                / Math.max(original.frobeniusNorm(), 1e-300);
    }
}
