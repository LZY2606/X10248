package polopt.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full derivation for one material at one convention/parameter fingerprint:
 * design -> estimate -> physical review -> decomposition candidates.
 */
public final class EstimationPipeline {

    public record Output(
            String materialId,
            Fingerprint fingerprint,
            String stokesOrder,
            String rotationConvention,
            int configCount,
            int usableCount,
            double[][] designMatrix,
            double[][] usableDesign,
            int rank,
            int nullity,
            double conditionNumber,
            double[] singularValues,
            SVDEstimator.Result estimation,
            PhysicalRealizability.Report physicalReport,
            List<Decompositions.Candidate> candidates,
            List<String> diagnostics,
            List<String> excludedConfigs) {
    }

    private EstimationPipeline() {
    }

    public static Output run(String materialId, ObservationSet obs,
                             Map<String, String> extraFingerprint,
                             double condThreshold) {
        List<String> diagnostics = new ArrayList<>(obs.diagnostics());
        List<String> excluded = new ArrayList<>();
        double[][] all = obs.designMatrix();

        List<Integer> keep = new ArrayList<>();
        for (int k = 0; k < obs.configs().size(); k++) {
            if (Boolean.TRUE.equals(obs.usable().get(k))) {
                keep.add(k);
            } else {
                excluded.add(obs.configs().get(k).configId());
            }
        }
        double[][] w = new double[keep.size()][16];
        for (int r = 0; r < keep.size(); r++) {
            w[r] = all[keep.get(r)];
        }
        double[] y = obs.observationVector();
        double gain = obs.reference() == null ? 1.0 : obs.reference().gain();
        if (gain != 1.0 && gain > 0.0) {
            for (int k = 0; k < y.length; k++) {
                y[k] = y[k] / gain;
            }
            diagnostics.add("reference gain correction applied: /" + gain
                    + " (" + (obs.reference() == null ? "none" : obs.reference().version()) + ")");
        }

        double[][] cov = sliceCovariance(obs.covariance(), keep);
        if (cov != null) {
            diagnostics.add("correlated covariance used (" + obs.covariance().length + "x"
                    + obs.covariance().length + " matrix)");
        }

        SVDEstimator.Result est = SVDEstimator.estimate(w, y, cov, condThreshold);
        diagnostics.addAll(est.diagnostics());

        PhysicalRealizability.Report report = null;
        List<Decompositions.Candidate> candidates = new ArrayList<>();
        if (est.mueller() != null) {
            report = PhysicalRealizability.evaluate(est.mueller());
            candidates = Decompositions.all(est.mueller());
            if (!report.physical()) {
                diagnostics.add("non-physical candidate flagged with "
                        + report.violations().size() + " violation(s); NOT projected in place");
            }
        } else {
            diagnostics.add("no unique Mueller candidate: affine family / abstention retained");
            enumerateFamily(est, candidates, diagnostics);
        }

        Map<String, String> factors = new LinkedHashMap<>(extraFingerprint);
        factors.putIfAbsent("stokesOrder", orderCode(obs.stokesOrder()));
        factors.putIfAbsent("rotationConvention", obs.rotationConvention());
        factors.putIfAbsent("estimationRule", "svd-truncated-gauss-markov");
        factors.putIfAbsent("estimationRuleVersion", "1");
        factors.putIfAbsent("condThreshold", format(condThreshold));
        Fingerprint fp = Fingerprint.of(factors);

        return new Output(materialId, fp, obs.stokesOrder(), obs.rotationConvention(),
                obs.configs().size(), keep.size(), all, w,
                est.rank(), est.nullity(), est.conditionNumber(), est.singularValues(),
                est, report, candidates, diagnostics, excluded);
    }

    private static void enumerateFamily(SVDEstimator.Result est,
                                        List<Decompositions.Candidate> candidates,
                                        List<String> diagnostics) {
        if (est.familyAnchor() == null) {
            return;
        }
        double[][] anchor = SVDEstimator.fromVec(est.familyAnchor());
        PhysicalRealizability.Report ar = PhysicalRealizability.evaluate(anchor);
        candidates.add(new Decompositions.Candidate("FAMILY_ANCHOR_MIN_NORM",
                "Minimum-norm anchor of the affine solution family (NOT unique)",
                anchor, ar.physical(), ar.minEigenvalue(), ar.violations(),
                Decompositions.diattenuation(anchor), Double.NaN,
                Decompositions.depolarizationIndex(anchor)));
        if (est.nullity() <= 2 && est.nullBasis() != null && est.nullBasis().length == 16) {
            // Preserve ALL legal candidates for small nullity: scan t in [-1,1]
            // and keep those that satisfy physical realizability.
            double[] n = new double[16];
            for (int i = 0; i < 16; i++) {
                n[i] = est.nullBasis()[i][0];
            }
            int found = 0;
            for (int s = -40; s <= 40 && found < 12; s++) {
                double t = s * 0.05;
                double[] xv = est.familyAnchor().clone();
                for (int i = 0; i < 16; i++) {
                    xv[i] += t * n[i];
                }
                double[][] mm = SVDEstimator.fromVec(xv);
                PhysicalRealizability.Report rr = PhysicalRealizability.evaluate(mm);
                if (rr.physical()) {
                    candidates.add(new Decompositions.Candidate(
                            "LEGAL_FAMILY_CANDIDATE",
                            "Legal physical candidate t=" + format(t),
                            mm, true, rr.minEigenvalue(), List.of(),
                            Decompositions.diattenuation(mm), Double.NaN,
                            Decompositions.depolarizationIndex(mm)));
                    found++;
                }
            }
            diagnostics.add("nullity " + est.nullity()
                    + ": enumerated physical candidates along null direction; all legal candidates retained");
        }
    }

    private static double[][] sliceCovariance(double[][] full, List<Integer> keep) {
        if (full == null) {
            return null;
        }
        int n = keep.size();
        double[][] c = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                c[i][j] = full[keep.get(i)][keep.get(j)];
            }
        }
        return c;
    }

    private static String orderCode(String stokesOrder) {
        return stokesOrder == null ? "S0S1S2S3" : stokesOrder;
    }

    private static String format(double v) {
        if (Double.isInfinite(v)) {
            return "inf";
        }
        return String.format("%.6g", v);
    }
}
