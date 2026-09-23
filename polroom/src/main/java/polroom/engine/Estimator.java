package polroom.engine;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import polroom.domain.Conventions;
import polroom.domain.LuChipman;
import polroom.domain.Mat;
import polroom.domain.PhysicalConstraints;
import polroom.domain.Polarization;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the measurement design matrix W (rows = psa_i * psg_j outer products)
 * and estimates vec(M) by truncated-SVD least squares.  Near-singular designs
 * are never naively inverted: singular directions are reported as a null space
 * and rank-deficient solutions are carried as an explicit candidate family.
 */
public final class Estimator {

    public enum Status {
        FULL_RANK,
        NEAR_SINGULAR,
        RANK_DEFICIENT
    }

    public static class MeasurementSpec {
        public double[] psg;
        public double[] psa;
        public double intensity;
        public double dark;
        public String label = "";
        public String unit = "count";

        public MeasurementSpec() {
        }
    }

    public static class Candidate {
        public String id;
        public double[][] matrix;
        public double[][] covariance;
        public PhysicalConstraints.Report physical;
        public List<LuChipman.Result> decompositions;
        public boolean feasible;
        public double familyParameter;
    }

    public static class Result {
        public Status status;
        public int rank;
        public double conditionNumber;
        public double[] singularValues;
        public double[][] designMatrix;
        public double[][] nullBasis;
        public double[][] minNormSolution;
        public double[][] minNormCovariance;
        public double[] fitted;
        public double residualRms;
        public List<Candidate> candidates = new ArrayList<>();
        public double tolerance;
    }

    /** Row ordering pairs with vec(M)[4*i+j] = M_ij: intensity = psa^T M psg. */
    public static double[] designRow(double[] psa, double[] psg) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                row[4 * i + j] = psa[i] * psg[j];
            }
        }
        return row;
    }

    public static RealMatrix designMatrix(List<MeasurementSpec> specs) {
        double[][] rows = new double[specs.size()][16];
        for (int k = 0; k < specs.size(); k++) {
            rows[k] = designRow(specs.get(k).psa, specs.get(k).psg);
        }
        return new Array2DRowRealMatrix(rows);
    }

    public static Result estimate(List<MeasurementSpec> specs, RealMatrix covariance,
                                  double svdTol, double nearSingularCondition) {
        Result result = new Result();
        result.tolerance = svdTol;
        RealMatrix w = designMatrix(specs);
        result.designMatrix = w.getData();
        Mat.Svd svd = Mat.svd(w);
        result.singularValues = svd.s();
        result.rank = svd.rank(svdTol);
        result.conditionNumber = svd.condition();

        int n = specs.size();
        double[] b = new double[n];
        for (int k = 0; k < n; k++) {
            b[k] = specs.get(k).intensity - specs.get(k).dark;
        }

        WeightedModel weighted = whiten(w, b, covariance);
        RealMatrix ww = weighted.ww;
        double[] bb = weighted.bb;

        double[] solution = Mat.mv(Mat.pinvTruncated(ww, svdTol), bb);
        RealMatrix gram = ww.transpose().multiply(ww);
        RealModel covarianceModel = covariance(solution, ww, bb, gram, svdTol);

        result.minNormSolution = toMatrix(solution);
        result.minNormCovariance = covarianceModel.covariance;
        result.fitted = new double[n];
        for (int k = 0; k < n; k++) {
            result.fitted[k] = Mat.dot(w.getRow(k), solution);
        }
        result.residualRms = residualRms(w, solution, b);

        if (result.rank < 16) {
            result.status = Status.RANK_DEFICIENT;
            RealMatrix nullBasis = Mat.nullspace(w, svdTol);
            result.nullBasis = nullBasis.getData();
            result.candidates = enumerateFamily(solution, covarianceModel.covariance, nullBasis);
            return result;
        }
        result.status = result.conditionNumber > nearSingularCondition
                ? Status.NEAR_SINGULAR : Status.FULL_RANK;
        result.candidates.add(candidateFor("min-norm", result.minNormSolution,
                result.minNormCovariance, 0.0));
        return result;
    }

    private record WeightedModel(RealMatrix ww, double[] bb) {
    }

    private static WeightedModel whiten(RealMatrix w, double[] b, RealMatrix covariance) {
        if (covariance == null) {
            return new WeightedModel(w, b);
        }
        Mat.Svd svd = Mat.svd(covariance);
        double smax = svd.s()[0];
        RealMatrix cInvSqrt = new Array2DRowRealMatrix(covariance.getRowDimension(),
                covariance.getColumnDimension());
        RealMatrix u = svd.u();
        for (int k = 0; k < svd.s().length; k++) {
            if (svd.s()[k] > 1e-12 * smax) {
                cInvSqrt = cInvSqrt.add(u.getColumnMatrix(k)
                        .multiply(u.getColumnMatrix(k).transpose())
                        .scalarMultiply(1.0 / Math.sqrt(svd.s()[k])));
            }
        }
        return new WeightedModel(cInvSqrt.multiply(w), cInvSqrt.operate(b));
    }

    private record RealModel(double[][] covariance) {
    }

    private static RealModel covariance(double[] solution, RealMatrix ww, double[] bb,
                                        RealMatrix gram, double tol) {
        RealMatrix pinvGram = Mat.pinvTruncated(gram, tol);
        int dof = Math.max(1, bb.length - 16);
        double s = 0;
        for (int k = 0; k < bb.length; k++) {
            double pred = Mat.dot(ww.getRow(k), solution);
            s += (pred - bb[k]) * (pred - bb[k]);
        }
        double sigma2 = s / dof;
        return new RealModel(pinvGram.scalarMultiply(sigma2).getData());
    }

    private static double residualRms(RealMatrix w, double[] m, double[] b) {
        double s = 0;
        for (int k = 0; k < b.length; k++) {
            double pred = Mat.dot(w.getRow(k), m);
            s += (pred - b[k]) * (pred - b[k]);
        }
        return Math.sqrt(s / Math.max(1, b.length));
    }

    private static List<Candidate> enumerateFamily(double[] x0, double[][] covariance,
                                                   RealMatrix nullBasis) {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(candidateFor("family:alpha=0", toMatrix(x0), covariance, 0.0));
        if (nullBasis.getColumnDimension() == 1) {
            double[] n = nullBasis.getColumn(0);
            double[] interval = feasibleInterval(x0, n);
            double lo = interval[0];
            double hi = interval[1];
            if (Double.isFinite(lo) && Double.isFinite(hi) && hi >= lo) {
                double[] alphas = {lo, (lo + hi) / 2, hi};
                for (int i = 0; i < alphas.length; i++) {
                    double[] sol = add(x0, n, alphas[i]);
                    candidates.add(candidateFor("family:alpha=" + i, toMatrix(sol),
                            covariance, alphas[i]));
                }
            } else {
                for (double alpha : new double[]{-1.0, 1.0}) {
                    candidates.add(candidateFor("family:alpha=" + alpha,
                            toMatrix(add(x0, n, alpha)), covariance, alpha));
                }
            }
        }
        return candidates;
    }

    /** Bracket the alpha interval over which the candidate is physically feasible. */
    private static double[] feasibleInterval(double[] x0, double[] n) {
        double lo = 0;
        double hi = 0;
        for (int dir : new int[]{-1, 1}) {
            double step = 1e-4;
            double feasibleA = 0;
            double a = 0;
            boolean everInfeasible = false;
            for (int it = 0; it < 80; it++) {
                double na = a + dir * step;
                if (!physicalFeasible(toMatrix(add(x0, n, na)))) {
                    everInfeasible = true;
                    double l = feasibleA;
                    double rr = na;
                    for (int b = 0; b < 40; b++) {
                        double mid = (l + rr) / 2;
                        if (physicalFeasible(toMatrix(add(x0, n, mid)))) {
                            l = mid;
                        } else {
                            rr = mid;
                        }
                    }
                    if (dir < 0) {
                        lo = l;
                    } else {
                        hi = l;
                    }
                    break;
                }
                feasibleA = na;
                a = na;
                step *= 1.25;
                if (Math.abs(a) > 1e6) {
                    break;
                }
            }
            if (!everInfeasible) {
                if (dir < 0) {
                    lo = Double.NEGATIVE_INFINITY;
                } else {
                    hi = Double.POSITIVE_INFINITY;
                }
            }
        }
        return new double[]{lo, hi};
    }

    private static boolean physicalFeasible(double[][] matrix) {
        return PhysicalConstraints.check(new Array2DRowRealMatrix(matrix)).physicallyRealizable;
    }

    private static double[] add(double[] x, double[] n, double alpha) {
        double[] out = x.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] += alpha * n[i];
        }
        return out;
    }

    private static Candidate candidateFor(String id, double[][] matrix, double[][] covariance,
                                          double familyParameter) {
        Candidate c = new Candidate();
        c.id = id;
        c.matrix = matrix;
        c.covariance = covariance;
        c.familyParameter = familyParameter;
        RealMatrix mm = new Array2DRowRealMatrix(matrix);
        c.physical = PhysicalConstraints.check(mm);
        c.feasible = c.physical.physicallyRealizable;
        c.decompositions = LuChipman.candidates(mm);
        return c;
    }

    private static double[][] toMatrix(double[] vec) {
        double[][] out = new double[4][4];
        for (int i = 0; i < 16; i++) {
            out[i / 4][i % 4] = vec[i];
        }
        return out;
    }

    /** Decode a textual optical-state code to a Stokes vector in the declared order. */
    public static double[] decodeState(String code, double angleDeg, double ellipticityDeg,
                                       Conventions.StokesOrder order,
                                       List<String> customLabels,
                                       Conventions.RotationConvention rotation) {
        double theta = Math.toRadians(angleDeg);
        double[] canonical;
        switch (code) {
            case "L" -> canonical = Polarization.linearStokes(theta, rotation);
            case "E" -> canonical = Polarization.ellipticalStokes(theta,
                    Math.toRadians(ellipticityDeg), rotation);
            case "H" -> canonical = Polarization.linearStokes(0, rotation);
            case "V" -> canonical = Polarization.linearStokes(90, rotation);
            case "P" -> canonical = Polarization.linearStokes(45, rotation);
            case "M" -> canonical = Polarization.linearStokes(-45, rotation);
            case "R" -> canonical = new double[]{1, 0, 0, 1};
            case "LCP" -> canonical = new double[]{1, 0, 0, -1};
            default -> throw new IllegalArgumentException("unknown optical state code: " + code);
        }
        return applyOrder(canonical, order, customLabels);
    }

    private static double[] applyOrder(double[] shv, Conventions.StokesOrder order,
                                       List<String> customLabels) {
        if (order == Conventions.StokesOrder.SHV) {
            return shv;
        }
        if (order == Conventions.StokesOrder.SIV) {
            return new double[]{shv[0], shv[2], shv[3], shv[1]};
        }
        double[] out = new double[4];
        String[] canon = {"I", "Q", "U", "V"};
        for (int i = 0; i < 4; i++) {
            int idx = customLabels.indexOf(canon[i]);
            if (idx >= 0) {
                out[idx] = shv[i];
            }
        }
        return out;
    }

    private Estimator() {
    }
}
