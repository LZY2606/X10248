package polworkspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CasePackage {
    public static final String CASE_ID = "POL-MUELLER-ROOM-2026";
    private CasePackage() {}

    public static Json.Obj build() {
        List<Json.Obj> sources = new ArrayList<>();
        List<double[]> states = standardStates();
        double[][] trueMueller = linearRetarder(Math.toRadians(30), Math.toRadians(72));
        double[][] nonphysicalMueller = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, -1}};
        Json.Arr intensity = intensities(states, trueMueller, 5);
        Json.Arr nonphysicalIntensity = intensities(states, nonphysicalMueller, 5);
        add(sources, "src-intensity-v1", "intensity", "v1", "raw_counts", "count", "detector pixel; row=generator*6+analyzer",
                Json.obj().str("shape", "36x1").obj("values", intensity).str("acquisition", "multiple generator/analyzer states")
                        .obj("unconverted_values", Json.arr(Json.obj().str("stable_channel", "overflow-channel-37").str("value", ">99999").str("reason", "no finite count; retained in original representation"))));
        add(sources, "src-intensity-v2", "intensity", "v2", "raw_counts", "count", "detector pixel; row=generator*6+analyzer",
                Json.obj().str("shape", "36x1").obj("values", nonphysicalIntensity).str("acquisition", "noisy non-physical realization"));
        add(sources, "src-states-v1", "optical_state", "v1", "generator_analyzer_pair", "dimensionless normalized Stokes", "laboratory x/y propagation z",
                statePayload(states, "S0S1S2S3", "RIGHT_HANDED_POSITIVE", false));
        add(sources, "src-states-v3", "optical_state", "v3", "generator_analyzer_pair", "dimensionless normalized Stokes", "laboratory x/y propagation z",
                statePayload(states, "S0S1S3S2", "RIGHT_HANDED_POSITIVE", false));
        add(sources, "src-states-v4", "optical_state", "v4", "generator_analyzer_pair", "dimensionless normalized Stokes", "laboratory x/y propagation z",
                statePayload(states, "S0S1S2S3", "LEFT_HANDED_POSITIVE", false));
        add(sources, "src-states-v5", "optical_state", "v5", "generator_analyzer_pair", "dimensionless restricted Stokes; singular design", "laboratory x/y propagation z",
                singularStatePayload());
        add(sources, "src-dark-v1", "dark", "v1", "dark_offset", "count", "detector pixel", vectorPayload(repeat(36, 5.0)));
        add(sources, "src-dark-v2", "dark", "v2", "dark_offset", "count", "detector pixel; local correction channel 17", vectorPayload(withOne(repeat(36, 5.0), 17, 7.0)));
        add(sources, "src-reference-v1", "reference_sample", "v1", "calibration_reference", "dimensionless Mueller entries", "laboratory x/y propagation z",
                Json.obj().str("sample_id", "identity-reference").num("scale_factor", 1.0).obj("reference_mueller", matrixArray(Matrix.identity(4).a)).str("unit", "dimensionless"));
        add(sources, "src-reference-v2", "reference_sample", "v2", "calibration_reference", "dimensionless Mueller entries", "laboratory x/y propagation z",
                Json.obj().str("sample_id", "identity-reference").num("scale_factor", 0.98).obj("reference_mueller", matrixArray(Matrix.identity(4).a)).str("unit", "dimensionless"));
        add(sources, "src-instrument-v1", "instrument_matrix", "v1", "flattened_state_to_mueller_operator", "dimensionless", "S0S1S2S3; RIGHT_HANDED_POSITIVE",
                Json.obj().str("instrument_id", "identity-state-operator").obj("matrix", matrixArray(Matrix.identity(16).a)));
        add(sources, "src-wavelength-v1", "wavelength", "v1", "nominal_laser_wavelength", "nm", "vacuum wavelength",
                Json.obj().num("value", 532.0).str("unit", "nm").str("basis", "vacuum").str("original_text", "532 nm nominal laser line"));
        add(sources, "src-wavelength-v2", "wavelength", "v2", "nominal_laser_wavelength", "nm", "vacuum wavelength after spectrometer revision",
                Json.obj().num("value", 532.2).str("unit", "nm").str("basis", "vacuum").str("original_text", "532.2 nm calibrated laser line"));
        add(sources, "src-covariance-v1", "covariance", "v1", "detector_count_covariance", "count^2", "same detector ordering as intensity",
                Json.obj().str("model", "diagonal").obj("values", repeat(36, 1.0)));
        add(sources, "src-covariance-v2", "covariance", "v2", "detector_count_covariance", "count^2", "same detector ordering as intensity; adjacent-channel correlation",
                covariancePayload());

        Json.Arr branches = Json.arr();
        addBranch(branches, "baseline-full-rank", "Baseline full-rank Mueller estimate", null,
                List.of("src-intensity-v1", "src-states-v1", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "BASELINE", "FULL_RANK_PHYSICAL_REVIEW");
        addBranch(branches, "dark-revision-local", "Dark-field revision limited to dependent channel", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v1", "src-dark-v2", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "DARK_FIELD_REVISION", "LOCAL_INVALIDATION_RECORDED");
        addBranch(branches, "stokes-order-change", "Stokes ordering changed in encoded optical states", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v3", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "STOKES_ORDER_CHANGE", "STOKES_ORDER_IN_FINGERPRINT");
        addBranch(branches, "rotation-sign-change", "Rotation positive-angle convention reversed", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v4", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "ROTATION_SIGN_CHANGE", "ROTATION_SIGN_IN_FINGERPRINT");
        addBranch(branches, "singular-design", "Rank-deficient design matrix without forced inverse", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v5", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "SINGULAR_DESIGN_MATRIX", "NEAR_SINGULAR_DESIGN_NOT_INVERTED");
        addBranch(branches, "nonphysical-noise", "Nonphysical noisy Mueller candidate retained with violations", "baseline-full-rank",
                List.of("src-intensity-v2", "src-states-v1", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "NONPHYSICAL_NOISE_SOLUTION", "NONPHYSICAL_FLAGGED_NOT_PROJECTED");
        addBranch(branches, "reference-revision", "Reference-sample version changes correction scale", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v1", "src-dark-v1", "src-reference-v2", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v1"),
                "REFERENCE_REVISION", "REFERENCE_SCOPE_INVALIDATION");
        addBranch(branches, "correlated-error", "Correlated detector covariance used in error propagation", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v1", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v1", "src-covariance-v2"),
                "CORRELATED_ERROR", "CORRELATED_COVARIANCE_WHITENED");
        addBranch(branches, "parameter-wavelength-revision", "Wavelength parameter revision invalidates wavelength-dependent interpretation", "baseline-full-rank",
                List.of("src-intensity-v1", "src-states-v1", "src-dark-v1", "src-reference-v1", "src-instrument-v1", "src-wavelength-v2", "src-covariance-v1"),
                "PARAMETER_REVISION", "PARAMETER_SCOPE_INVALIDATION");

        return Json.obj().str("case_id", CASE_ID)
                .str("title", "偏振矩阵室：Mueller 测量、仪器校正与物理可实现性审查")
                .str("schema", "polarimetric-mueller-room-case/1")
                .str("unit_policy", "raw representations, units and coordinate bases are preserved; normalization occurs only in derived runs")
                .obj("sources", sources)
                .obj("branches", branches);
    }

    private static void add(List<Json.Obj> sources, String id, String type, String version, String role, String unit, String basis, Json.Obj payload) {
        payload.str("id", id).str("source_type", type).str("version", version).str("role", role)
                .str("unit", unit).str("coordinate_basis", basis)
                .obj("diagnostics", payload.containsKey("unconverted_values")
                        ? Json.arr(Json.obj().str("code", "UNCONVERTED_VALUE_PRESERVED").str("stable_channel", "overflow-channel-37").bool("blocks_normalization", false))
                        : Json.arr());
        sources.add(payload);
    }

    private static Json.Obj vectorPayload(double[] values) {
        return Json.obj().obj("values", values);
    }

    private static double[] repeat(int n, double value) {
        double[] values = new double[n];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static double[] withOne(double[] values, int index, double value) {
        values[index] = value;
        return values;
    }

    private static Json.Arr matrixArray(double[][] matrix) {
        Json.Arr rows = Json.arr();
        for (double[] row : matrix) rows.add(row);
        return rows;
    }

    private static List<double[]> standardStates() {
        double[] angles = {0, Math.PI / 4, Math.PI / 2, 3 * Math.PI / 4, 0, Math.PI / 2};
        List<double[]> result = new ArrayList<>();
        for (int g = 0; g < 6; g++) for (int a = 0; a < 6; a++) {
            double[] generator = g < 4
                    ? new double[] {1, Math.cos(2 * angles[g]), Math.sin(2 * angles[g]), 0}
                    : new double[] {1, 0, 0, g == 4 ? 1 : -1};
            double[] analyzer = a < 4
                    ? new double[] {1, Math.cos(2 * angles[a]), Math.sin(2 * angles[a]), 0}
                    : new double[] {1, 0, 0, a == 4 ? 1 : -1};
            result.add(new double[] {g, a,
                    generator[0], generator[1], generator[2], generator[3],
                    analyzer[0], analyzer[1], analyzer[2], analyzer[3]});
        }
        return result;
    }

    private static Json.Obj statePayload(List<double[]> states, String order, String sign, boolean singular) {
        Json.Arr rows = Json.arr();
        for (int i = 0; i < states.size(); i++) {
            double[] s = states.get(i);
            rows.add(Json.obj().num("row", i).num("generator_index", (int) s[0]).num("analyzer_index", (int) s[1])
                    .obj("generator", stateVector(s[2], s[3], s[4], s[5]))
                    .obj("analyzer", stateVector(s[6], s[7], s[8], s[9]))
                    .str("angle_unit", "rad")
                    .str("stokes_order", order)
                    .str("rotation_sign", sign));
        }
        return Json.obj().str("stokes_order", order).str("rotation_sign", sign).obj("states", rows);
    }

    private static Json.Obj singularStatePayload() {
        double[] angles = {0, Math.PI / 4, Math.PI / 2};
        Json.Arr rows = Json.arr();
        for (int g = 0; g < 3; g++) for (int a = 0; a < 3; a++)
            for (int repeat = 0; repeat < 4; repeat++) {
                double[] generator = {1, Math.cos(2 * angles[g]), Math.sin(2 * angles[g]), 0};
                double[] analyzer = {1, Math.cos(2 * angles[a]), Math.sin(2 * angles[a]), 0};
                rows.add(Json.obj().num("row", rows.size()).num("generator_index", g).num("analyzer_index", a)
                        .obj("generator", stateVector(generator[0], generator[1], generator[2], generator[3]))
                        .obj("analyzer", stateVector(analyzer[0], analyzer[1], analyzer[2], analyzer[3]))
                        .str("angle_unit", "rad").str("stokes_order", "S0S1S2S3").str("rotation_sign", "RIGHT_HANDED_POSITIVE"));
            }
        return Json.obj().str("stokes_order", "S0S1S2S3").str("rotation_sign", "RIGHT_HANDED_POSITIVE").obj("states", rows);
    }

    private static Json.Obj stateVector(double s0, double s1, double s2, double s3) {
        return Json.obj().num("S0", s0).num("S1", s1).num("S2", s2).num("S3", s3).str("unit", "normalized Stokes");
    }

    private static double[][] linearRetarder(double theta, double delta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta), d = Math.cos(delta), e = Math.sin(delta);
        return new double[][] {
                {1, 0, 0, 0},
                {0, c * c + s * s * d, s * c * (1 - d), -s * e},
                {0, s * c * (1 - d), s * s + c * c * d, c * e},
                {0, s * e, -c * e, d}
        };
    }

    private static Json.Arr intensities(List<double[]> states, double[][] mueller, double noise) {
        Json.Arr values = Json.arr();
        int i = 0;
        for (double[] s : states) {
            double[] generator = {s[2], s[3], s[4], s[5]};
            double[] analyzer = {s[6], s[7], s[8], s[9]};
            double count = 1000;
            double total = 0;
            for (int r = 0; r < 4; r++) {
                double component = 0;
                for (int c = 0; c < 4; c++) component += mueller[r][c] * generator[c];
                total += analyzer[r] * component;
            }
            values.add(Math.max(0, count * total + noise * ((i % 3) - 1) + (i % 7) * 0.01));
            i++;
        }
        return values;
    }

    private static Json.Obj covariancePayload() {
        Json.Arr entries = Json.arr();
        for (int i = 0; i < 36; i++) entries.add(Json.obj().num("row", i).num("column", i).num("value", 1.0));
        for (int i = 0; i < 35; i++) {
            entries.add(Json.obj().num("row", i).num("column", i + 1).num("value", 0.18));
            entries.add(Json.obj().num("row", i + 1).num("column", i).num("value", 0.18));
        }
        return Json.obj().str("model", "sparse tridiagonal").str("unit", "count^2").obj("entries", entries);
    }

    private static void addBranch(Json.Arr branches, String key, String title, String parent, List<String> sourceIds,
                                  String eventType, String judgement) {
        Json.Arr refs = Json.arr();
        for (String id : sourceIds) refs.add(Json.obj().str("source_id", id).str("required", "true"));
        branches.add(Json.obj().str("branch_key", key).str("title", title).str("parent_branch_key", parent == null ? "" : parent)
                .obj("source_refs", refs).str("event_type", eventType).str("machine_judgement", judgement));
    }
}
