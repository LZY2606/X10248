package pol.casepack;

import pol.domain.Convention;
import pol.service.EstimationService;
import pol.service.ImportService;
import pol.service.Room;
import pol.service.State;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministically replays the full narrative into a fresh room:
 *  - separated baseline layers (intensity, encoding, dark, reference,
 *    instrument, wavelength, covariance)
 *  - one parameter revision (dark-frame revision)
 *  - one local-only raw change (single material)
 *  - Stokes-order change, rotation-sign flip, nonphysical noise injection,
 *    reference revision, correlated-error covariance
 *  - a second branch that keeps a singular design un-inverted and all legal
 *    candidates, plus a conflicting human decision.
 */
public final class Seeder {

    public static final String MAIN = "main-baseline";
    public static final String SINGULAR = "singular-investigation";
    public static final String MAT_A = "sample-A";
    public static final String MAT_B = "sample-B";

    private final Room room;
    private final ImportService importer;
    private final EstimationService estimator;
    private final Random rng = new Random(20260923L);
    private final Map<String, String> estimateIds = new LinkedHashMap<>();
    private final List<Map<String, Object>> breadcrumbs = new ArrayList<>();

    public Seeder(Room room) {
        this.room = room;
        this.importer = new ImportService(room);
        this.estimator = new EstimationService(room);
    }

    public Map<String, String> estimateIds() {
        return estimateIds;
    }

    public List<Map<String, Object>> breadcrumbs() {
        return breadcrumbs;
    }
}

class SeedContext {
    int head = 0;

    String src(String branch, String kind, String label, String material, String media,
               String content, String version, String revisionOf, String directSource,
               Map<String, String> attrs) {
        ImportService.Imported in = importer.importSource(branch, kind, label, material,
                media, content.getBytes(java.nio.charset.StandardCharsets.UTF_8), version,
                revisionOf, directSource, attrs, head, "seeder");
        head++;
        crumb(branch, kind + " v" + version, in.eventId);
        return in.eventId;
    }

    String est(String branch, String material, String label) {
        Map<String, Object> r = estimator.estimate(branch, material, label, head, "seeder");
        pol.store.Event e = (pol.store.Event) r.get("event");
        head++;
        crumb(branch, "estimate " + label, e.id);
        return e.id;
    }

    String decide(String branch, String estimateId, String decision, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("estimateId", estimateId);
        payload.put("decision", decision);
        payload.put("note", note);
        payload.put("revert", false);
        pol.store.Event e = room.append(branch, "decision", payload, List.of(estimateId),
                "seeder", head, null, null);
        head++;
        crumb(branch, "decision " + decision + " " + note, e.id);
        return e.id;
    }

    void note(String branch, String text) {
        room.append(branch, "note", Map.of("text", text), List.of(), "seeder", head, null, null);
        head++;
    }
}

/** Entry point: seed both branches and leave MAIN accepted, SINGULAR in conflict-ready state. */
public void seed() {
    room.ensureBranch(MAIN, "seeder", "baseline import with all edge-case re-interpretations");
    SeedContext ctx = new SeedContext();

    baseline(ctx);
    darkRevision(ctx);
    stokesOrderChange(ctx);
    rotationFlip(ctx);
    nonphysicalNoise(ctx);
    referenceRevision(ctx);
    correlatedErrors(ctx);
    localRawEdit(ctx);
    forkSingular(ctx);
}

private void crumb(String branch, String what, String eventId) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("branch", branch);
    c.put("what", what);
    c.put("eventId", eventId);
    breadcrumbs.add(c);
}

private void baseline(SeedContext c) {
    CasePack.RawCsv csvA = CasePack.baselineCsv(CasePack.trueMuellerA(), "A", 20.0, 3.0, rng);
    CasePack.RawCsv csvB = CasePack.baselineCsv(CasePack.trueMuellerB(), "B", 18.0, 4.0, rng);

    c.src(MAIN, "raw_intensities", "raw detector counts sample A", MAT_A, "text/csv",
            csvA.text, "1", null, null, attrs("unit", "count", "rowCount", String.valueOf(csvA.usableRows)));
    c.src(MAIN, "raw_intensities", "raw detector counts sample B", MAT_B, "text/csv",
            csvB.text, "1", null, null, attrs("unit", "count", "rowCount", String.valueOf(csvB.usableRows)));
    c.src(MAIN, "state_encoding", "standard optical state codebook", null, "application/json",
            standardEncoding(), "1", null, null, standardEncodingAttrs());
    c.src(MAIN, "dark_frame", "lab dark frame baseline", null, "text/csv",
            "dark_counts\n20.0\n", "1", null, null, attrs("value_counts", "20.0", "unit", "count"));
    c.src(MAIN, "reference_sample", "air reference gain", null, "text/csv",
            "sample,gain_counts_per_unit\nair,1000.0\n", "1", null, null,
            attrs("gain_counts_per_unit", "1000.0", "unit", "count/unit-transmittance"));
    c.src(MAIN, "instrument_matrix", "nominal PSG/PSA instrument matrix W", null, "text/csv",
            CasePack.instrumentMatrixCsv(), "1", null, null, instrumentAttrs(1.0, 0.98, 0.97, 0.96));
    c.src(MAIN, "wavelength", "HeNe laser wavelength", MAT_A, "text/plain",
            CasePack.WAVELENGTH_NM + "\n", "1", null, null, attrs("unit", "nm"));
    c.src(MAIN, "wavelength", "HeNe laser wavelength", MAT_B, "text/plain",
            CasePack.WAVELENGTH_NM + "\n", "1", null, null, attrs("unit", "nm"));
    c.src(MAIN, "covariance", "independent detector covariance", null, "text/csv",
            "sigma_counts,correlation_rho\n3.0,0.0\n", "1", null, null,
            attrs("sigma_counts", "3.0", "correlation_rho", "0.0", "unit", "count^2"));

    estimateIds.put("A-baseline", c.est(MAIN, MAT_A, "baseline Mueller A (full 5x5 design)"));
    estimateIds.put("B-baseline", c.est(MAIN, MAT_B, "baseline Mueller B (full 5x5 design)"));
    c.decide(MAIN, estimateIds.get("A-baseline"), "accept", "baseline accepted after source review");
}

private void darkRevision(SeedContext c) {
    String old = latestSource(MAIN, "dark_frame");
    c.src(MAIN, "dark_frame", "revised dark frame after cooled-detector recalibration", null,
            "text/csv", "dark_counts\n12.0\n", "2", old, old,
            attrs("value_counts", "12.0", "unit", "count"));
    estimateIds.put("A-darkv2", c.est(MAIN, MAT_A, "Mueller A with revised dark v2"));
}

private void stokesOrderChange(SeedContext c) {
    Map<String, String> attrs = standardEncodingAttrs();
    attrs.put("stokesOrder", "S0,S1,S3,S2");
    String old = latestSourceForMaterial(MAIN, "state_encoding", MAT_A);
    c.src(MAIN, "state_encoding", "ALTERNATE Stokes order S0,S1,S3,S2 (encoding variant)", MAT_A,
            "application/json", swappedEncoding(), "2", old, old, attrs);
    estimateIds.put("A-stokes-swapped", c.est(MAIN, MAT_A,
            "Mueller A under swapped Stokes order (different fingerprint)"));
}

private void rotationFlip(SeedContext c) {
    Map<String, String> attrs = standardEncodingAttrs();
    attrs.put("rotationSign", "active-positive-CW");
    String old = latestSourceForMaterial(MAIN, "state_encoding", MAT_A);
    c.src(MAIN, "state_encoding", "flipped rotation sign (CW positive) variant", MAT_A,
            "application/json", flippedEncoding(), "3", old, old, attrs);
    estimateIds.put("A-rotation-flipped", c.est(MAIN, MAT_A,
            "Mueller A under CW-positive rotation (different fingerprint)"));
}

private void nonphysicalNoise(SeedContext c) {
    CasePack.RawCsv clean = CasePack.baselineCsv(CasePack.trueMuellerA(), "A", 20.0, 3.0, rng);
    String noisy = injectNonphysical(clean.text);
    String old = latestSourceForMaterial(MAIN, "raw_intensities", MAT_A);
    c.src(MAIN, "raw_intensities", "RAW CANDIDATE with huge noise excursions (nonphysical fit)",
            MAT_A, "text/csv", noisy, "2-noise", old, old,
            attrs("unit", "count", "note", "deliberately nonphysical; must be flagged, never projected"));
    String id = c.est(MAIN, MAT_A, "nonphysical noise candidate A (flag violation, do not project)");
    estimateIds.put("A-nonphysical", id);
}

private void referenceRevision(SeedContext c) {
    String old = latestSource(MAIN, "reference_sample");
    c.src(MAIN, "reference_sample", "revised air reference gain after lamp replacement", null,
            "text/csv", "sample,gain_counts_per_unit\nair,1035.0\n", "2", old, old,
            attrs("gain_counts_per_unit", "1035.0", "unit", "count/unit-transmittance"));
    estimateIds.put("A-refv2", c.est(MAIN, MAT_A, "Mueller A under reference gain v2"));
}

private void correlatedErrors(SeedContext c) {
    String old = latestSource(MAIN, "covariance");
    c.src(MAIN, "covariance", "correlated detector noise (rho=0.35)", null, "text/csv",
            "sigma_counts,correlation_rho\n3.0,0.35\n", "2", old, old,
            attrs("sigma_counts", "3.0", "correlation_rho", "0.35", "unit", "count^2"));
    estimateIds.put("A-corr", c.est(MAIN, MAT_A, "Mueller A with correlated-error covariance v2"));
    estimateIds.put("B-corr", c.est(MAIN, MAT_B, "Mueller B with correlated-error covariance v2"));
}

private void localRawEdit(SeedContext c) {
    CasePack.RawCsv csvB = CasePack.baselineCsv(CasePack.trueMuellerB(), "B", 18.0, 4.0, new Random(20260923L));
    String edited = CasePack.withLocalEdit(csvB.text, "B-M13", 40.0,
            "local-only edit: single analyzer/generator cell changed");
    String old = latestSourceForMaterial(MAIN, "raw_intensities", MAT_B);
    c.src(MAIN, "raw_intensities", "material B raw with ONE local cell edited", MAT_B,
            "text/csv", edited, "2-local", old, old, attrs("unit", "count",
                    "note", "local dependency only: A estimates must remain unaffected"));
    estimateIds.put("B-local-edit", c.est(MAIN, MAT_B, "Mueller B after local raw edit"));
}

private void forkSingular(SeedContext c) {
    int forkAt = c.head;
    room.forkBranch(MAIN, SINGULAR, "seeder",
            "rank-deficient design: no forced inverse, keep null space and legal candidates");
    SeedContext sc = new SeedContext();
    sc.head = forkAt;
    String singularCsv = CasePack.singularCsv(CasePack.trueMuellerA());
    sc.src(SINGULAR, "raw_intensities", "SINGULAR design (H/V only, rank < 16)", MAT_A, "text/csv",
            singularCsv, "1-singular", null, null,
            attrs("unit", "count", "design", "H/V only: S2,S3 unobservable"));
    String sid = sc.est(SINGULAR, MAT_A, "minimum-norm Mueller A for SINGULAR design (not unique)");
    estimateIds.put("A-singular", sid);
    sc.decide(SINGULAR, sid, "reject", "not uniquely determined; null space retained");
}

private static Map<String, String> attrs(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
        m.put(kv[i], kv[i + 1]);
    }
    return m;
}

private static Map<String, String> standardEncodingAttrs() {
    Map<String, String> m = attrs("stokesOrder", "S0,S1,S2,S3",
            "rotationSign", "active-positive-CCW",
            "angleZero", "analyzer-transmission-axis-at-0deg=horizontal",
            "angleUnit", "deg", "unit", "1");
    return m;
}

private static Map<String, String> instrumentAttrs(double d0, double d1, double d2, double d3) {
    Map<String, String> m = attrs("unit", "1", "coordinateBasis", "Stokes-normalized");
    m.put("w_generator", "1 0 0 0; 0 " + d0 + " 0 0; 0 0 " + d0 + " 0; 0 0 0 " + d0);
    m.put("w_analyzer", "1 0 0 0; 0 " + d1 + " 0 0; 0 0 " + d2 + " 0; 0 0 0 " + d3);
    return m;
}

private static String standardEncoding() {
    return "{\"order\":[\"S0\",\"S1\",\"S2\",\"S3\"],\"rotationSign\":\"active-positive-CCW\","
            + "\"codes\":{\"H\":\"[1,1,0,0]\",\"V\":\"[1,-1,0,0]\",\"P\":\"[1,0,1,0]\","
            + "\"M\":\"[1,0,-1,0]\",\"R\":\"[1,0,0,1]\",\"L\":\"[1,0,0,-1]\"}}";
}

private static String swappedEncoding() {
    return "{\"order\":[\"S0\",\"S1\",\"S3\",\"S2\"],\"rotationSign\":\"active-positive-CCW\","
            + "\"note\":\"component positions 3 and 4 swapped; enters fingerprint\"}";
}

private static String flippedEncoding() {
    return "{\"order\":[\"S0\",\"S1\",\"S2\",\"S3\"],\"rotationSign\":\"active-positive-CW\","
            + "\"note\":\"positive angle now clockwise; enters fingerprint\"}";
}

private String latestSource(String branch, String kind) {
    State state = room.state(branch);
    State.Source out = null;
    for (State.Source s : state.sources.values()) {
        if (s.kind.equals(kind) && (s.materialId == null || s.materialId.isBlank()) && s.active) {
            out = s;
        }
    }
    return out == null ? null : out.id;
}

private String latestSourceForMaterial(String branch, String kind, String material) {
    State state = room.state(branch);
    State.Source out = null;
    for (State.Source s : state.sources.values()) {
        if (s.kind.equals(kind) && material.equals(s.materialId) && s.active) {
            out = s;
        }
    }
    return out == null ? null : out.id;
}

/**
 * Add gross excursions (saturation and negative-after-dark counts) that drive a
 * least-squares Mueller fit outside the physical cone. The raw counts remain
 * exactly as imported; only the derived candidate becomes nonphysical and must
 * be flagged rather than projected.
 */
private static String injectNonphysical(String csv) {
    StringBuilder out = new StringBuilder();
    int n = 0;
    for (String line : csv.split("\\r?\\n", -1)) {
        if (line.isEmpty() || line.startsWith("measurement_id")) {
            out.append(line).append('\n');
            continue;
        }
        String[] c = line.split(",", -1);
        if (c.length >= 6) {
            n++;
            if (n % 5 == 0) {
                c[4] = String.format(java.util.Locale.ROOT, "%.3f", 65000.0);
                c[5] = "saturation excursion injected";
            } else if (n % 7 == 0) {
                c[4] = String.format(java.util.Locale.ROOT, "%.3f", -5000.0);
                c[5] = "negative count excursion injected";
            }
            line = String.join(",", c);
        }
        out.append(line).append('\n');
    }
    return out.toString();
}

