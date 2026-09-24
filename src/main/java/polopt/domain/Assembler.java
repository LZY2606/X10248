package polopt.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns provenance-separated source documents into an {@link ObservationSet}.
 *
 * The raw layer keeps original representations: an intensity that cannot be
 * converted to a usable number stays in its original position with a diagnostic
 * and is excluded from estimation (never dropped from the record).
 */
public final class Assembler {

    public static class AssemblyException extends RuntimeException {
        public AssemblyException(String message) {
            super(message);
        }
    }

    private Assembler() {
    }

    public record Sources(
            SourceDoc rawIntensity,
            SourceDoc opticalStates,
            SourceDoc dark,
            SourceDoc reference,
            SourceDoc instrument,
            SourceDoc wavelength,
            SourceDoc covariance) {

        public Map<String, SourceDoc> byType() {
            Map<String, SourceDoc> m = new LinkedHashMap<>();
            put(m, SourceDoc.TYPE_RAW_INTENSITY, rawIntensity);
            put(m, SourceDoc.TYPE_OPTICAL_STATES, opticalStates);
            put(m, SourceDoc.TYPE_DARK, dark);
            put(m, SourceDoc.TYPE_REFERENCE, reference);
            put(m, SourceDoc.TYPE_INSTRUMENT, instrument);
            put(m, SourceDoc.TYPE_WAVELENGTH, wavelength);
            put(m, SourceDoc.TYPE_COVARIANCE, covariance);
            return m;
        }

        private void put(Map<String, SourceDoc> m, String k, SourceDoc d) {
            if (d != null) {
                m.put(k, d);
            }
        }
    }

    public static ObservationSet assemble(String materialId, Sources sources) {
        require(sources.rawIntensity() != null, "missing rawIntensity source");
        require(sources.opticalStates() != null, "missing opticalStates source");
        JsonNode rawNode = sources.rawIntensity().payload();
        JsonNode statesNode = sources.opticalStates().payload();

        String stokesOrder = textOrDefault(rawNode, "stokesOrder",
                textOrDefault(statesNode, "stokesOrder", "S0S1S2S3"));
        String rotation = textOrDefault(rawNode, "rotationConvention",
                textOrDefault(statesNode, "rotationConvention", "ccw-positive"));
        List<String> order = parseOrder(stokesOrder);

        Map<String, StateEncoding> states = parseStates(statesNode, rotation);
        List<ObservationSet.Config> configs = new ArrayList<>();
        List<Double> intensities = new ArrayList<>();
        List<Boolean> usable = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        JsonNode rows = rawNode.get("measurements");
        require(rows != null && rows.isArray(), "rawIntensity.measurements must be an array");
        for (int k = 0; k < rows.size(); k++) {
            JsonNode row = rows.get(k);
            String configId = row.path("configId").asText("cfg-" + k);
            String gid = row.path("generatorStateId").asText(null);
            String aid = row.path("analyzerStateId").asText(null);
            require(states.containsKey(gid), "unknown generator state: " + gid);
            require(states.containsKey(aid), "unknown analyzer state: " + aid);

            double[] gCan = Optics.stateStokes(states.get(gid), rotation);
            double[] aCan = Optics.stateStokes(states.get(aid), rotation);
            double[] g = Optics.reorder(gCan, order);
            double[] a = Optics.reorder(aCan, order);
            configs.add(new ObservationSet.Config(configId, gid, aid, g, a));

            JsonNode valNode = row.get("intensity");
            String unit = row.path("unit").asText(sources.rawIntensity().unit() == null
                    ? "count" : sources.rawIntensity().unit());
            String basis = row.path("basis").asText(rawNode.path("basis").asText("detector"));
            Units.Q q = Units.parse(jsonScalar(valNode), unit, basis);
            if (q.convertible()) {
                intensities.add(q.valueSi());
                usable.add(true);
            } else {
                intensities.add(Double.NaN);
                usable.add(false);
                diagnostics.add("CONFIG " + configId + ": " + Units.describe(q)
                        + " — kept at original position, excluded from estimation");
            }
        }

        double[] dark = parseDark(sources.dark());
        CovParse cov = parseCovariance(sources.covariance(), rows.size());
        double[][] instrument = parseInstrument(sources.instrument());
        Wl wl = parseWavelength(sources.wavelength());
        ObservationSet.ReferenceModel ref = parseReference(sources.reference());

        return new ObservationSet(materialId, configs, intensities, usable, diagnostics,
                dark[0], dark[1], cov.matrix(), cov.scale(), ref, instrument,
                wl.nm(), wl.basis(), stokesOrder, rotation);
    }

    private static Object jsonScalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }

    private static List<String> parseOrder(String code) {
        if (code == null) {
            return List.of("S0", "S1", "S2", "S3");
        }
        if ("S0S1S2S3".equals(code)) {
            return List.of("S0", "S1", "S2", "S3");
        }
        if ("S0S3S2S1".equals(code)) {
            return List.of("S0", "S3", "S2", "S1");
        }
        // generic parse like "S0,S1,S2,S3"
        List<String> parts = new ArrayList<>();
        for (String p : code.split(",")) {
            parts.add(p.trim());
        }
        require(parts.size() == 4 && parts.containsAll(List.of("S0", "S1", "S2", "S3")),
                "invalid stokesOrder: " + code);
        return parts;
    }

    private static Map<String, StateEncoding> parseStates(JsonNode node, String rotation) {
        Map<String, StateEncoding> map = new LinkedHashMap<>();
        JsonNode arr = node.get("states");
        require(arr != null && arr.isArray(), "opticalStates.states must be an array");
        for (JsonNode s : arr) {
            String id = s.path("id").asText(null);
            require(id != null, "state missing id");
            String role = s.path("role").asText("GENERATOR");
            String basis = s.path("basis").asText("laboratory");
            String rc = s.path("rotationConvention").asText(rotation);
            List<StateEncoding.Element> els = new ArrayList<>();
            JsonNode en = s.path("elements");
            if (en.isArray()) {
                for (JsonNode e : en) {
                    els.add(new StateEncoding.Element(e.path("kind").asText(),
                            e.path("axisDeg").asDouble()));
                }
            }
            List<Double> input = null;
            if (s.has("inputStokes") && s.get("inputStokes").isArray()) {
                input = new ArrayList<>();
                for (JsonNode v : s.get("inputStokes")) {
                    input.add(v.asDouble());
                }
            }
            map.put(id, new StateEncoding(id, role, basis, rc, els, input));
        }
        return map;
    }

    private static double[] parseDark(SourceDoc doc) {
        if (doc == null) {
            return new double[] { 0.0, 0.0 };
        }
        JsonNode p = doc.payload();
        double d = p.path("darkCount").asDouble(0.0);
        double u = p.path("darkUncertainty").asDouble(0.0);
        return new double[] { d, u };
    }

    private record CovParse(double[][] matrix, double scale) {
    }

    private static CovParse parseCovariance(SourceDoc doc, int n) {
        if (doc == null) {
            return new CovParse(null, 1.0);
        }
        JsonNode p = doc.payload();
        double scale = p.path("scale").asDouble(1.0);
        JsonNode m = p.get("matrix");
        if (m != null && m.isArray() && m.size() > 0 && m.get(0).isArray()) {
            double[][] c = new double[m.size()][m.size()];
            for (int i = 0; i < m.size(); i++) {
                for (int j = 0; j < m.size(); j++) {
                    c[i][j] = m.get(i).get(j).asDouble();
                }
            }
            return new CovParse(c, scale);
        }
        JsonNode diag = p.get("diagonal");
        double[][] c = new double[n][n];
        if (diag != null && diag.isArray()) {
            for (int i = 0; i < n; i++) {
                c[i][i] = scale * diag.get(Math.min(i, diag.size() - 1)).asDouble(1.0);
            }
        } else {
            double v = p.path("variance").asDouble(1.0);
            for (int i = 0; i < n; i++) {
                c[i][i] = scale * v;
            }
        }
        return new CovParse(c, scale);
    }

    private static double[][] parseInstrument(SourceDoc doc) {
        if (doc == null) {
            return identity4();
        }
        JsonNode m = doc.payload().get("mueller");
        if (m == null) {
            return identity4();
        }
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                r[i][j] = m.get(i).get(j).asDouble();
            }
        }
        return r;
    }

    private static double[][] identity4() {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            m[i][i] = 1.0;
        }
        return m;
    }

    private record Wl(double nm, String basis) {
    }

    private static Wl parseWavelength(SourceDoc doc) {
        if (doc == null) {
            return new Wl(Double.NaN, "unspecified");
        }
        String unit = doc.unit() == null ? "nm" : doc.unit();
        Units.Q q = Units.parse(doc.payload().path("wavelength").asDouble(),
                unit, doc.payload().path("basis").asText("vacuum"));
        double nm = q.convertible() ? q.valueSi() : Double.NaN;
        return new Wl(nm, q.basis());
    }

    private static ObservationSet.ReferenceModel parseReference(SourceDoc doc) {
        if (doc == null) {
            return ObservationSet.ReferenceModel.none();
        }
        JsonNode p = doc.payload();
        String version = doc.version() == null ? "v?" : doc.version();
        double gain = p.path("gain").asDouble(1.0);
        double[] stokes = null;
        double[][] mueller = null;
        if (p.has("knownStokes")) {
            stokes = new double[4];
            for (int i = 0; i < 4; i++) {
                stokes[i] = p.get("knownStokes").get(i).asDouble();
            }
        }
        if (p.has("knownMueller")) {
            mueller = new double[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    mueller[i][j] = p.get("knownMueller").get(i).get(j).asDouble();
                }
            }
        }
        return new ObservationSet.ReferenceModel(version, gain, stokes, mueller);
    }

    private static String textOrDefault(JsonNode n, String key, String def) {
        if (n != null && n.has(key)) {
            return n.get(key).asText(def);
        }
        return def;
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new AssemblyException(msg);
        }
    }
}
