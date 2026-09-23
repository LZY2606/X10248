package pol.service;

import pol.domain.Convention;
import pol.domain.Decomp;
import pol.domain.Estimator;
import pol.domain.Measurements;
import pol.domain.Physical;
import pol.domain.PolarStates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the current interpreted state, builds calibrated observations, runs the
 * estimator and appends an immutable estimate event. Every derived number keeps
 * the explicit list of source events it was computed from.
 */
public final class EstimationService {

    private final Room room;

    public EstimationService(Room room) {
        this.room = room;
    }

    public synchronized Map<String, Object> estimate(String branch, String materialId, String label,
                                                     int expectedHead, String actor) {
        State state = room.state(branch);

        State.Source raw = latest(state, "raw_intensities", materialId);
        State.Source encoding = latest(state, "state_encoding", materialId);
        State.Source dark = latestGlobal(state, "dark_frame");
        State.Source reference = latestGlobal(state, "reference_sample");
        State.Source instrument = latestGlobal(state, "instrument_matrix");
        State.Source wavelength = latest(state, "wavelength", materialId);
        State.Source covariance = latestGlobal(state, "covariance");
        if (raw == null) {
            throw new IllegalArgumentException("no raw intensities for material " + materialId);
        }
        if (encoding == null) {
            throw new IllegalArgumentException("no optical state encoding for material " + materialId);
        }

        Convention convention = conventionOf(encoding);
        byte[] blob = room.database().getBlob(raw.blobId);
        if (blob == null) {
            throw new IllegalStateException("raw blob missing " + raw.blobId);
        }
        Measurements.Parse parsed = Measurements.parse(new String(blob, java.nio.charset.StandardCharsets.UTF_8));

        double darkValue = dark == null ? 0.0 : num(dark.attributes, "value_counts", 0.0);
        double gain = reference == null ? 1.0 : num(reference.attributes, "gain_counts_per_unit", 1.0);
        double[][] wg = instrument == null ? null : matrix(instrument.attributes, "w_generator");
        double[][] wa = instrument == null ? null : matrix(instrument.attributes, "w_analyzer");

        PolarStates states = new PolarStates(convention, wg, wa);

        List<Estimator.Observation> observations = new ArrayList<>();
        List<String> includedMeasurementIds = new ArrayList<>();
        for (Measurements.Row row : parsed.rows) {
            if (!row.intensity.convertible || !row.wavelength.convertible) {
                continue;
            }
            double[] g;
            double[] a;
            try {
                g = states.generator(row.generatorCode);
                a = states.analyzer(row.analyzerCode);
            } catch (Exception bad) {
                continue;
            }
            double calibrated = (row.intensity.numeric - darkValue) / gain;
            observations.add(new Estimator.Observation(g, a, calibrated));
            includedMeasurementIds.add(row.measurementId);
        }

        double[][] cov = buildCovariance(covariance, observations.size());

        Estimator.Result result = Estimator.estimate(observations, cov, convention);
        Physical.Report report = Physical.check(result.mueller);
        List<Decomp.Candidate> candidates = Decomp.all(result.mueller);

        List<String> sourceIds = new ArrayList<>();
        addSource(sourceIds, raw);
        addSource(sourceIds, encoding);
        addSource(sourceIds, dark);
        addSource(sourceIds, reference);
        addSource(sourceIds, instrument);
        addSource(sourceIds, wavelength);
        addSource(sourceIds, covariance);

        double cond = result.conditionNumber;
        boolean nearSingular = !result.unique || Double.isInfinite(cond) || cond > 1e9;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "estimate_created");
        payload.put("materialId", materialId);
        payload.put("label", label);
        payload.put("stokesOrder", convention.orderCode());
        payload.put("rotationSign", convention.rotationSign);
        payload.put("fingerprint", convention.fingerprint());
        payload.put("conditionNumber", finite(cond));
        payload.put("singularValues", result.singularValues);
        payload.put("rank", result.rank);
        payload.put("nullity", result.nullity);
        payload.put("unique", result.unique);
        payload.put("nearSingular", nearSingular);
        payload.put("designThreshold", result.designThreshold);
        payload.put("residualNorm", result.residualNorm);
        payload.put("mueller", result.mueller);
        payload.put("elementSigma", result.elementSigma);
        payload.put("nullspace", result.nullspace);
        payload.put("observationCount", observations.size());
        payload.put("includedMeasurementIds", includedMeasurementIds);
        payload.put("physical", physicalMap(report));
        payload.put("decompositions", decompMap(candidates));
        payload.put("calibration", calibMap(dark, darkValue, reference, gain, instrument, covariance, wavelength));

        return Map.of("event", room.append(branch, "estimate_created", payload,
                sourceIds, actor, expectedHead, null, null),
                "payload", payload,
                "observations", observations,
                "report", report,
                "candidates", candidates);
    }

    private static Map<String, Object> physicalMap(Physical.Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realizable", r.realizable);
        m.put("coherencyPsd", r.coherencyPsd);
        m.put("minCoherencyEigenvalue", r.minCoherencyEigenvalue);
        m.put("entropy", r.entropy);
        m.put("depolarizationIndex", r.depolarizationIndex);
        m.put("passivityOk", r.passivityOk);
        m.put("stokesBoundsOk", r.stokesBoundsOk);
        List<Map<String, Object>> vios = new ArrayList<>();
        for (Physical.Violation v : r.violations) {
            Map<String, Object> vv = new LinkedHashMap<>();
            vv.put("name", v.name);
            vv.put("amount", v.amount);
            vv.put("detail", v.detail);
            vios.add(vv);
        }
        m.put("violations", vios);
        return m;
    }

    private static List<Map<String, Object>> decompMap(List<Decomp.Candidate> candidates) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Decomp.Candidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind);
            m.put("note", c.note);
            m.put("reconstructionResidual", c.reconstructionResidual);
            List<Map<String, Object>> comps = new ArrayList<>();
            for (Decomp.Component comp : c.components) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", comp.name);
                cm.put("matrix", comp.matrix);
                cm.put("parameterSummary", comp.parameterSummary);
                comps.add(cm);
            }
            m.put("components", comps);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> calibMap(State.Source dark, double darkValue,
                                                State.Source ref, double gain,
                                                State.Source inst, State.Source cov,
                                                State.Source wl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("darkEventId", dark == null ? null : dark.id);
        m.put("darkCounts", darkValue);
        m.put("referenceEventId", ref == null ? null : ref.id);
        m.put("gainCountsPerUnit", gain);
        m.put("instrumentEventId", inst == null ? null : inst.id);
        m.put("covarianceEventId", cov == null ? null : cov.id);
        m.put("covarianceVersion", cov == null ? null : cov.version);
        m.put("wavelengthEventId", wl == null ? null : wl.id);
        return m;
    }

    private double[][] buildCovariance(State.Source cov, int n) {
        double sigma = 1.0;
        double rho = 0.0;
        if (cov != null) {
            sigma = num(cov.attributes, "sigma_counts", 1.0);
            rho = num(cov.attributes, "correlation_rho", 0.0);
        }
        double scale = cov != null && cov.attributes.containsKey("gain_counts_per_unit_at_estimate")
                ? 1.0 : 1.0;
        double[][] c = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                c[i][j] = sigma * sigma * Math.pow(Math.abs(rho), Math.abs(i - j)) / scale;
            }
        }
        return c;
    }

    private Convention conventionOf(State.Source encoding) {
        String order = enc(encoding, "stokesOrder", "S0,S1,S2,S3");
        String rot = enc(encoding, "rotationSign", "active-positive-CCW");
        String zero = enc(encoding, "angleZero", "analyzer-transmission-axis-at-0deg=horizontal");
        String angleUnit = enc(encoding, "angleUnit", "deg");
        return new Convention(java.util.Arrays.asList(order.split(",")), rot, zero, angleUnit);
    }

    private static String enc(State.Source s, String key, String dflt) {
        return s.attributes.getOrDefault(key, dflt);
    }

    private static void addSource(List<String> ids, State.Source s) {
        if (s != null && !ids.contains(s.id)) {
            ids.add(s.id);
        }
    }

    private static double num(Map<String, String> attrs, String key, double dflt) {
        String v = attrs.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return dflt;
        }
    }

    private static double[][] matrix(Map<String, String> attrs, String key) {
        String raw = attrs.get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] rows = raw.split(";");
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            String[] parts = rows[i].trim().split("\\s+");
            for (int j = 0; j < 4; j++) {
                m[i][j] = Double.parseDouble(parts[j]);
            }
        }
        return m;
    }

    static State.Source latest(State state, String kind, String materialId) {
        State.Source out = null;
        for (State.Source s : state.sources.values()) {
            if (s.kind.equals(kind) && materialId.equals(s.materialId) && s.active) {
                out = s;
            }
        }
        return out;
    }

    static State.Source latestGlobal(State state, String kind) {
        State.Source out = null;
        for (State.Source s : state.sources.values()) {
            if (s.kind.equals(kind) && s.active) {
                out = s;
            }
        }
        return out;
    }

    private static Object finite(double v) {
        return Double.isInfinite(v) ? null : v;
    }
}
