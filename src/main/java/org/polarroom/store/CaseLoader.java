package org.polarroom.store;

import org.polarroom.core.Models;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the bundled polarisation case pack into an empty database and replays
 * its narrative as branch events. Loading is one transaction: any failure
 * rolls the whole import back, so no half-loaded case can remain.
 */
public final class CaseLoader {

    private CaseLoader() {
    }

    public static final String MAIN = "br_main";
    public static final String SINGULAR = "br_singular";
    public static final String UNPHYSICAL = "br_unphysical";

    public static boolean isLoaded(Db db) {
        return "polarroom-case-1.0".equals(db.meta("case"));
    }

    public static void load(Db db) {
        db.transaction(d -> {
            if (isLoaded(d)) {
                return null;
            }
            CaseData.Bundle b = CaseData.generate();

            // --- source versions (seven separated material kinds) ---
            String vStates1 = put(d, "optical_state", 1,
                    "states_v1.csv", b.statesCsv, "canonical order, sign +");
            String vStates2 = put(d, "optical_state", 2,
                    "states_v2.csv", b.statesCsv, "canonical order, sign -");
            String vStates3 = put(d, "optical_state", 3,
                    "states_v3.csv", b.statesCsvReordered,
                    "header S0,S2,S1,S3 (Stokes order change)");

            put(d, "intensity", 1, "intensity_v1.csv", b.intensityCsv,
                    "baseline 16 PSG x PSA measurements");
            put(d, "intensity", 2, "intensity_v2.csv", b.intensityUnphysicalCsv,
                    "noise realisation producing unphysical candidate");
            put(d, "intensity", 3, "intensity_v3.csv", b.intensityLinearCsv,
                    "linear-only states giving rank-9 design");

            put(d, "dark", 1, "dark_v1.csv", b.darkCsvV1, "baseline dark counts");
            put(d, "dark", 2, "dark_v2.csv", b.darkCsvV2, "dark revision (+15)");

            put(d, "reference", 1, "reference_v1.csv", b.referenceCsvV1,
                    "reference sample v1");
            put(d, "reference", 2, "reference_v2.csv", b.referenceCsvV2,
                    "reference sample v2 (gain ~0.9)");

            put(d, "instrument", 1, "instrument_v1.csv", b.instrumentV1,
                    "identity instrument matrix");
            put(d, "instrument", 2, "instrument_v2.csv", b.instrumentV2,
                    "calibrated instrument matrix");

            put(d, "wavelength", 1, "wavelength.csv", b.wavelengthCsv,
                    "632.8 nm HeNe");

            put(d, "covariance", 1, "covariance_v1.csv", b.covarianceV1,
                    "independent diagonal covariance");
            put(d, "covariance", 2, "covariance_v2.csv", b.covarianceV2,
                    "correlated covariance");

            // --- main branch narrative ---
            createBranch(d, MAIN, "main review", null, null,
                    "baseline Mueller estimation review");
            String e1 = append(d, MAIN, "case_initial",
                    Map.of("material", CaseData.MATERIAL,
                            "description", "initial import: seven separated sources"),
                    null, "initial case import", null);
            bind(d, MAIN, "all v1 sources", versions(1, 1, 1, 1, 1, 1, 1));
            append(d, MAIN, "rule_update",
                    withInvalidation("rule:applyReferenceGain", "rules-1.0",
                            Map.of("applyReferenceGain", false),
                            "parameter revision: reference gain initially disabled"),
                    null, "parameter revision", null);
            // dark revision: new source version + new binding, local invalidation
            append(d, MAIN, "source_revision",
                    withInvalidation("dark", "dark#v1", "dark#v2",
                            Map.of("versions", Map.of("dark", 2)),
                            "dark field correction"),
                    null, "dark revision", null);
            bind(d, MAIN, "reference sample v2 enabled",
                    versions(1, 1, 2, 2, 1, 1, 1),
                    Map.of("applyReferenceGain", true));
            // correlated covariance: affects only error propagation
            append(d, MAIN, "source_revision",
                    withInvalidation("covariance", "covariance#v1", "covariance#v2",
                            Map.of("versions", Map.of("covariance", 2)),
                            "correlated error test"),
                    null, "correlated covariance", null);

            // Stokes order change (fingerprint must differ) on main
            append(d, MAIN, "source_revision",
                    withInvalidation("optical_state", "optical_state#v1",
                            "optical_state#v3",
                            Map.of("versions", Map.of("optical_state", 3)),
                            "Stokes order change S0,S2,S1,S3"),
                    null, "stokes order change", null);
            // rotation sign change
            append(d, MAIN, "rule_update",
                    withInvalidation("rule:rotationSign", "rotationSign=+1",
                            "rotationSign=-1",
                            Map.of("rotationSign", -1,
                                    "versions", Map.of("optical_state", 2)),
                            "rotation sign reversed"),
                    null, "rotation sign revision", null);
            // restore canonical v1 conventions for the final main state
            append(d, MAIN, "rule_update",
                    Map.of("ruleVersion", "rules-1.0",
                            "rotationSign", 1,
                            "versions", Map.of("optical_state", 1),
                            "note", "canonical conventions restored"),
                    null, "conventions restored", null);

            // accepted and rejected decomposition candidates
            ruling(d, MAIN, "decomp_forward", "accepted",
                    "forward Lu-Chipman adopted for reporting");
            ruling(d, MAIN, "decomp_backward", "rejected",
                    "backward decomposition kept but not adopted");

            // --- singular design branch (fork from main head) ---
            Models.Branch main = d.getBranch(MAIN);
            createBranch(d, SINGULAR, "singular design review",
                    MAIN, main.headEventId,
                    "near-singular design must not be force-inverted");
            String sBase = append(d, SINGULAR, "source_revision",
                    withInvalidation("optical_state", "optical_state#v1",
                            "optical_state#linear",
                            Map.of("versions", Map.of(
                                    "optical_state", 3, "intensity", 3)),
                            "linear-only states"),
                    null, "linear-only design", null);
            // v3 intensity (linear) binding; keep optical v? use linear state file
            bindAt(d, SINGULAR, "bind linear measurements",
                    versions(3, 3, 2, 2, 2, 1, 2), null, null);
            append(d, SINGULAR, "rule_update",
                    withInvalidation("rule:rankRelTol", "rankRelTol=1E-9",
                            "rankRelTol=1E-12",
                            Map.of("parameters", Map.of("rankRelTol", 1e-12)),
                            "near-singular threshold tightening"),
                    null, "rank tolerance revision", null);
            ruling(d, SINGULAR, "fit", "rejected",
                    "unique fit rejected: design matrix nullity present");

            // --- unphysical branch (fork from main) ---
            createBranch(d, UNPHYSICAL, "unphysical noise review",
                    MAIN, main.headEventId,
                    "unphysical candidate flagged, never projected into fit");
            append(d, UNPHYSICAL, "source_revision",
                    withInvalidation("intensity", "intensity#v1", "intensity#v2",
                            Map.of("versions", Map.of("intensity", 2)),
                            "large-noise realisation"),
                    null, "unphysical noise realisation", null);
            bindAt(d, UNPHYSICAL, "bind unphysical measurements",
                    versions(2, 1, 2, 2, 2, 1, 2), null, null);
            ruling(d, UNPHYSICAL, "fit", "rejected",
                    "unphysical Mueller candidate flagged, not projected");

            d.putMeta("case", "polarroom-case-1.0");
            d.putMeta("material", CaseData.MATERIAL);
            d.putMeta("wavelength_nm", String.valueOf(CaseData.WAVELENGTH_NM));
            return null;
        });
    }

    private static Map<String, Integer> versions(int intensity, int state, int dark,
                                                 int reference, int instrument,
                                                 int wavelength, int covariance) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("intensity", intensity);
        m.put("optical_state", state);
        m.put("dark", dark);
        m.put("reference", reference);
        m.put("instrument", instrument);
        m.put("wavelength", wavelength);
        m.put("covariance", covariance);
        return m;
    }

    private static String put(Db db, String material, int v, String filename,
                              String content, String note) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String sha = db.putBlob(bytes, "text/csv");
        Models.SourceVersion sv = new Models.SourceVersion();
        sv.materialId = material;
        sv.type = Models.MaterialType.fromCode(material);
        sv.versionNo = v;
        sv.blobSha = sha;
        sv.originalFilename = filename;
        sv.note = note;
        db.addSourceVersion(sv);
        db.putUnitBasis(material, v, "value",
                material.equals("wavelength") ? "nm"
                        : material.equals("covariance") ? "count^2" : "count");
        return sha;
    }

    private static void createBranch(Db db, String id, String name,
                                     String parent, String baseEvent, String purpose) {
        Models.Branch br = new Models.Branch();
        br.branchId = id;
        br.name = name;
        br.parentBranchId = parent;
        br.baseEventId = baseEvent;
        br.purpose = purpose;
        br.createdAt = System.currentTimeMillis();
        db.insertBranch(br);
    }

    private static String append(Db db, String branch, String kind, Object payload,
                                 String expected, String note, String revertedOf) {
        String head = db.getBranch(branch).headEventId;
        return db.appendEvent(branch, kind, payload, head, note, revertedOf).eventId;
    }

    private static void bind(Db db, String branch, String note,
                             Map<String, Integer> versions) {
        bindAt(db, branch, note, versions,
                Map.of("ruleVersion", "rules-1.0",
                        "stokesOrder", "S0,S1,S2,S3",
                        "rotationSign", 1), null);
    }

    private static void bindAt(Db db, String branch, String note,
                               Map<String, Integer> versions,
                               Map<String, Object> ruleExtras) {
        db.appendEvent(branch, "role_binding",
                Map.of("versions", versions,
                        "ruleVersion", "rules-1.0",
                        "stokesOrder", "S0,S1,S2,S3",
                        "rotationSign", 1,
                        "note", note == null ? "" : note),
                db.getBranch(branch).headEventId, note, null);
    }

    private static void bind(Db db, String branch, String note,
                             Map<String, Integer> versions,
                             Map<String, Object> ruleExtras) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("versions", versions);
        payload.put("ruleVersion", "rules-1.0");
        payload.put("stokesOrder", "S0,S1,S2,S3");
        payload.put("rotationSign", 1);
        payload.put("parameters", ruleExtras == null ? Map.of() : ruleExtras);
        payload.put("note", note);
        db.appendEvent(branch, "role_binding", payload,
                db.getBranch(branch).headEventId, note, null);
    }

    private static void ruling(Db db, String branch, String subject,
                               String decision, String note) {
        Models.Branch br = db.getBranch(branch);
        String evId = db.appendEvent(branch, "ruling",
                Map.of("subjectKey", subject, "decision", decision,
                        "note", note),
                br.headEventId, note, null);
        db.insertRuling(branch, subject, evId.eventId, decision, note);
    }

    private static Map<String, Object> withInvalidation(String change, String oldV,
                                                        String newV,
                                                        Map<String, Object> extra,
                                                        String note) {
        Map<String, Object> m = new LinkedHashMap<>(
                DependencyGraph.invalidationPayload(change, oldV, newV));
        m.putAll(extra);
        m.put("note", note);
        return m;
    }
}
