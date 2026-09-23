package pol.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the measurement design X and solves for the Mueller coefficients from
 * calibrated intensities. Dark subtraction, reference normalization, the
 * instrument matrix and covariance are applied at this derived layer only; the
 * imported raw layer is untouched.
 */
public final class Estimator {

    public static final class Observation {
        public final double generator[];
        public final double analyzer[];
        public final double intensity;

        public Observation(double[] generator, double[] analyzer, double intensity) {
            this.generator = generator;
            this.analyzer = analyzer;
            this.intensity = intensity;
        }
    }

    public static final class Result {
        public final double[][] mueller;
        public final double[][] elementSigma;
        public final double conditionNumber;
        public final double[] singularValues;
        public final int rank;
        public final int nullity;
        public final boolean unique;
        public final double designThreshold;
        public final double[][] nullspace;
        public final double residualNorm;
        public final String conventionFingerprint;

        Result(Linalg.Solve solve, String fp) {
            this.mueller = new double[4][4];
            this.elementSigma = new double[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    this.mueller[i][j] = solve.coefficients[i * 4 + j];
                    double v = solve.covariance[i * 4 + j][i * 4 + j];
                    this.elementSigma[i][j] = Math.sqrt(Math.max(0, v));
                }
            }
            this.conditionNumber = solve.svd.condition;
            this.singularValues = solve.svd.singular;
            this.rank = solve.svd.rank;
            this.nullity = 16 - solve.svd.rank;
            this.unique = solve.unique;
            this.designThreshold = solve.svd.threshold;
            this.nullspace = solve.nullspace;
            this.residualNorm = solve.residualNorm;
            this.conventionFingerprint = fp;
        }
    }

    private Estimator() {
    }

    public static double[][] designMatrix(List<Observation> observations) {
        double[][] x = new double[observations.size()][16];
        for (int k = 0; k < observations.size(); k++) {
            Observation o = observations.get(k);
            x[k] = Linalg.kronRow(o.analyzer, o.generator);
        }
        return x;
    }

    public static double condition(List<Observation> observations) {
        Linalg.Svd svd = Linalg.svd(new org.ejml.simple.SimpleMatrix(designMatrix(observations)));
        return svd.condition;
    }

    public static Result estimate(List<Observation> observations, double[][] covariance, Convention convention) {
        int n = observations.size();
        double[][] x = designMatrix(observations);
        double[] y = new double[n];
        for (int k = 0; k < n; k++) {
            y[k] = observations.get(k).intensity;
        }
        Linalg.Solve solve = Linalg.svdSolve(x, y, covariance);
        return new Result(solve, convention.shortFp());
    }

    /** All Mueller matrices M = M0 + N t for a 1-d nullspace, filtered to physical ones. */
    public static List<double[][]> candidateFamily(double[][] m0, double[][] nullspace,
                                                   double tMin, double tMax, int samples) {
        List<double[][]> out = new ArrayList<>();
        for (int s = 0; s <= samples; s++) {
            double t = tMin + (tMax - tMin) * s / samples;
            double[][] m = new double[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    m[i][j] = m0[i][j] + t * nullspace[0][i * 4 + j];
                }
            }
            out.add(m);
        }
        return out;
    }
}
