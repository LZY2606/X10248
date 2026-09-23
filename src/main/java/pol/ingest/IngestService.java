package pol.ingest;

import pol.domain.Ids;
import pol.domain.Json;
import pol.engine.Store;
import pol.math.Polar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingest keeps source and interpretation strictly separate.
 * The raw representation, unit and coordinate reference are always preserved;
 * only the derived layer canonicalizes. Values that cannot be converted stay in
 * raw_repr with parse_status=UNCONVERTIBLE and a diagnostic — nothing is dropped.
 *
 * A failed import rolls the whole batch back: no half records survive.
 */
public class IngestService {

    public static final String K_INTENSITY = "intensity";
    public static final String K_STATE_ENCODING = "state-encoding";
    public static final String K_DARK = "dark";
    public static final String K_REFERENCE = "reference";
    public static final String K_INSTRUMENT = "instrument-matrix";
    public static final String K_WAVELENGTH = "wavelength";
    public static final String K_COVARIANCE = "covariance";

    private final Store store;

    public IngestService(Store store) { this.store = store; }

    public static final class Ingested {
        public String sourceId, versionId;
        public int versionNo, eventSeq;
        public String parseStatus;
        public String fingerprint;
        public List<String> diagnostics = new ArrayList<>();
    }

    public Ingested ingest(String branch, Map<String, Object> input, Integer expectedHead, String clientToken) {
        String sourceId = str(input, "source_id");
        String kind = str(input, "kind");
        if (sourceId == null || kind == null)
            throw new IllegalArgumentException("source_id and kind are required");
        String materialId = strOrDefault(input, "material_id", "M-DEFAULT");
        String displayName = strOrDefault(input, "display_name", sourceId);
        String rawRepr = Json.write(input.get("raw") != null ? input.get("raw") : input);
        String unit = input.get("unit") == null ? null : String.valueOf(input.get("unit"));
        String coordRef = input.get("coordinate_ref") == null ? null : String.valueOf(input.get("coordinate_ref"));
        String rawSha = Ids.hash(rawRepr);

        List<String> diagnostics = new ArrayList<>();
        Map<String, Object> canonical = new LinkedHashMap<>();
        String parseStatus = "OK";
        String fingerprint = null;
        try {
            switch (kind) {
                case K_INTENSITY -> canonical = parseIntensity(input, diagnostics);
                case K_STATE_ENCODING -> { canonical = parseStateEncoding(input, diagnostics);
                    fingerprint = Polar.fingerprint(intOrDefault(canonical, "rotation_sign", 1),
                            (String) canonical.get("stokes_order"), sourceId); }
                case K_DARK -> canonical = parseNumericList(input, "dark", "count", diagnostics);
                case K_REFERENCE -> canonical = parseReference(input, diagnostics);
                case K_INSTRUMENT -> canonical = parseInstrument(input, diagnostics);
                case K_WAVELENGTH -> canonical = parseWavelength(input, diagnostics);
                case K_COVARIANCE -> canonical = parseCovariance(input, diagnostics);
                default -> throw new IllegalArgumentException("unknown source kind: " + kind);
            }
            if (!diagnostics.isEmpty()) parseStatus = "PARTIAL";
        } catch (UnconvertibleException e) {
            parseStatus = "UNCONVERTIBLE";
            diagnostics.add(e.getMessage());
            canonical.put("unconvertible", true);
        }

        String finalParseStatus = parseStatus;
        String finalFingerprint = fingerprint;
        Map<String, Object> result = store.tx(conn -> {
            int vno;
            try {
                store.upsertSource(conn, sourceId, kind, materialId, displayName, null, null, null);
                vno = store.nextVersionNo(sourceId);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            String versionId = sourceId + ":v" + vno;
            Map<String, Object> payload = Json.obj(
                    "action", "source-version",
                    "source_id", sourceId, "kind", kind, "material_id", materialId,
                    "version_id", versionId, "version_no", vno,
                    "raw_sha256", rawSha, "unit", unit, "coordinate_ref", coordRef,
                    "parse_status", finalParseStatus, "diagnostics", diagnostics,
                    "canonical", canonical,
                    "change_reason", input.getOrDefault("change_reason", "initial import"));
            if (finalFingerprint != null) payload.put("fingerprint", finalFingerprint);

            Map<String, Object> ev = store.appendEvent(branch, "source-version", Json.write(payload),
                    null, finalFingerprint, expectedHead, clientToken, null);
            int seq = (int) ev.get("seq");
            try {
                store.insertVersion(conn, versionId, sourceId, vno, rawRepr, unit, coordRef,
                        finalParseStatus.equals("UNCONVERTIBLE") ? null : Json.write(canonical),
                        finalParseStatus, Json.write(diagnostics), rawSha, seq);
                try (var p = conn.prepareStatement(
                        "UPDATE sources SET created_event = COALESCE(created_event, ?) WHERE source_id = ?")) {
                    p.setInt(1, seq); p.setString(2, sourceId); p.executeUpdate();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return Json.obj("seq", seq, "version_id", versionId, "vno", vno,
                    "event_id", ev.get("event_id"), "fingerprint", finalFingerprint);
        });

        Ingested out = new Ingested();
        out.sourceId = sourceId;
        out.versionId = (String) result.get("version_id");
        out.versionNo = (int) result.get("vno");
        out.eventSeq = (int) result.get("seq");
        out.parseStatus = parseStatus;
        out.fingerprint = fingerprint;
        out.diagnostics = diagnostics;
        return out;
    }

    // ---- parsers ----

    private Map<String, Object> parseIntensity(Map<String, Object> in, List<String> diag) {
        List<Double> values = numbers(in, "values", "intensity counts", diag);
        List<Map<String, Object>> pairs = new ArrayList<>();
        Object pairsRaw = in.get("pairs");
        if (pairsRaw instanceof List<?> pl) {
            for (Object o : pl) {
                Map<?, ?> pm = (Map<?, ?>) o;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("generator_state", String.valueOf(pm.get("generator_state")));
                row.put("analyzer_state", String.valueOf(pm.get("analyzer_state")));
                pairs.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", values);
        out.put("pairs", pairs);
        return out;
    }

    private Map<String, Object> parseStateEncoding(Map<String, Object> in, List<String> diag) {
        String order = strOrDefault(in, "stokes_order", "I,Q,U,V");
        try { Polar.orderMapping(order); }
        catch (Exception e) { throw new UnconvertibleException("invalid stokes_order '" + order + "': " + e.getMessage()); }
        int sign = intOrDefault(in, "rotation_sign", 1);
        if (sign != 1 && sign != -1)
            throw new UnconvertibleException("rotation_sign must be +1 or -1, got " + sign);
        List<Map<String, Object>> states = new ArrayList<>();
        Object rawStates = in.get("states");
        if (!(rawStates instanceof List<?> sl))
            throw new UnconvertibleException("state-encoding requires a 'states' list");
        for (Object o : sl) {
            Map<?, ?> sm = (Map<?, ?>) o;
            String id = String.valueOf(sm.get("id"));
            Object vecRaw = sm.get("stokes");
            List<Double> declared = new ArrayList<>();
            boolean bad = false;
            if (vecRaw instanceof List<?> vl) {
                for (Object x : vl) {
                    try { declared.add(((Number) x).doubleValue()); }
                    catch (Exception e) { bad = true; diag.add("state " + id + " contains non-numeric stokes component kept raw: " + x); }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("declared", declared);
            row.put("declared_order", order);
            if (!bad && declared.size() == 4) {
                double[] canon = Polar.toCanonical(declared.stream().mapToDouble(Double::doubleValue).toArray(), order);
                row.put("canonical", java.util.Arrays.stream(canon).boxed().toList());
                row.put("canonical_order", "I,Q,U,V");
            } else {
                row.put("canonical", null);
                if (!diag.contains("state " + id + " not converted to canonical order"))
                    diag.add("state " + id + " not converted to canonical order");
            }
            states.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stokes_order", order);
        out.put("rotation_sign", sign);
        out.put("states", states);
        return out;
    }

    private Map<String, Object> parseNumericList(Map<String, Object> in, String key, String expectUnit, List<String> diag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, numbers(in, "values", expectUnit, diag));
        return out;
    }

    private Map<String, Object> parseReference(Map<String, Object> in, List<String> diag) {
        Map<String, Object> out = parseNumericList(in, "reference", "count", diag);
        out.put("reference_m00", doubleOrDefault(in, "reference_m00", 1.0));
        out.put("sample_id", strOrDefault(in, "reference_sample_id", "REF-AIR"));
        return out;
    }

    private Map<String, Object> parseInstrument(Map<String, Object> in, List<String> diag) {
        Object rows = in.get("matrix");
        double[][] m = matrix4x4(rows, diag, "instrument matrix");
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (double[] r : m) { List<Double> rr = new ArrayList<>(); for (double v : r) rr.add(v); list.add(rr); }
        out.put("matrix", list);
        out.put("calibration_ref_version", in.get("calibration_ref_version"));
        return out;
    }

    private Map<String, Object> parseWavelength(Map<String, Object> in, List<String> diag) {
        Object v = in.get("value");
        String providedUnit = strOrDefault(in, "unit", "nm");
        double nm;
        try {
            double x = ((Number) v).doubleValue();
            nm = switch (providedUnit.trim()) {
                case "nm" -> x;
                case "um", "µm" -> x * 1000;
                case "m" -> x * 1e9;
                default -> throw new UnconvertibleException("unknown wavelength unit: " + providedUnit);
            };
        } catch (UnconvertibleException e) { throw e; }
        catch (Exception e) { throw new UnconvertibleException("wavelength value not numeric: " + v); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value_nm", nm);
        out.put("raw_value", v);
        out.put("raw_unit", providedUnit);
        return out;
    }

    private Map<String, Object> parseCovariance(Map<String, Object> in, List<String> diag) {
        Object rows = in.get("matrix");
        boolean correlated = Boolean.TRUE.equals(in.get("correlated"));
        double[][] cov;
        if (rows instanceof List<?> rl && !rl.isEmpty() && rl.get(0) instanceof List<?>) {
            int n = rl.size();
            cov = new double[n][n];
            for (int i = 0; i < n; i++) {
                List<?> row = (List<?>) ((List<?>) rows).get(i);
                for (int j = 0; j < n; j++) cov[i][j] = ((Number) row.get(j)).doubleValue();
            }
        } else {
            List<Double> diagVar = numbers(in, "diagonal", "variance", diag);
            cov = new double[diagVar.size()][diagVar.size()];
            for (int i = 0; i < diagVar.size(); i++) cov[i][i] = diagVar.get(i);
        }
        if (correlated) {
            boolean hasOffDiag = false;
            for (int i = 0; i < cov.length; i++) for (int j = i + 1; j < cov.length; j++)
                if (cov[i][j] != 0 || cov[j][i] != 0) hasOffDiag = true;
            if (!hasOffDiag) diag.add("correlated covariance declared but all off-diagonals are zero");
        }
        List<Object> list = new ArrayList<>();
        for (double[] r : cov) { List<Double> rr = new ArrayList<>(); for (double v : r) rr.add(v); list.add(rr); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matrix", list);
        out.put("correlated", correlated);
        return out;
    }

    private static List<Double> numbers(Map<String, Object> in, String key, String what, List<String> diag) {
        Object raw = in.get(key);
        List<Double> out = new ArrayList<>();
        if (!(raw instanceof List<?> list))
            throw new UnconvertibleException(what + " expects list under '" + key + "'");
        int idx = -1;
        for (Object o : list) {
            idx++;
            try {
                out.add(((Number) o).doubleValue());
            } catch (Exception e) {
                diag.add(what + "[" + idx + "] non-numeric, left in raw_repr only: " + o);
            }
        }
        return out;
    }

    private double[][] matrix4x4(Object rows, List<String> diag, String what) {
        if (!(rows instanceof List<?> rl) || rl.size() != 4)
            throw new UnconvertibleException(what + " must be 4 rows");
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            Object row = rl.get(i);
            if (!(row instanceof List<?> cl) || cl.size() != 4)
                throw new UnconvertibleException(what + " row " + i + " must have 4 entries");
            for (int j = 0; j < 4; j++) m[i][j] = ((Number) cl.get(j)).doubleValue();
        }
        return m;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
    private static String strOrDefault(Map<String, Object> m, String k, String d) {
        String v = str(m, k); return v == null ? d : v;
    }
    private static int intOrDefault(Map<String, Object> m, String k, int d) {
        Object v = m.get(k); if (v == null) return d;
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }
    private static double doubleOrDefault(Map<String, Object> m, String k, double d) {
        Object v = m.get(k); if (v == null) return d;
        return ((Number) v).doubleValue();
    }

    public static class UnconvertibleException extends RuntimeException {
        public UnconvertibleException(String m) { super(m); }
    }
}
