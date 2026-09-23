package polar.service;

import com.fasterxml.jackson.databind.JsonNode;
import polar.db.Database;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Source import. Raw bytes are stored verbatim and versioned per source id;
 * interpretation happens only in the derived layer. A failed batch rolls back
 * completely: no half-written records are left behind.
 */
public final class ImportService {
    private ImportService() {}

    public record RawSource(String id, String kind, int version,
                            String unit, String basis, String payloadJson) {}

    public static List<String> importSources(Database db, String branchId, List<RawSource> batch) {
        // validate everything before touching the database
        List<String> errors = new ArrayList<>();
        for (RawSource s : batch) {
            String err = validate(s);
            if (err != null) errors.add(s.id() + ": " + err);
            if (db.source(s.id(), s.version()) != null)
                errors.add(s.id() + "@" + s.version() + " already exists");
        }
        if (!errors.isEmpty())
            throw new IllegalArgumentException("import rejected, nothing written: " + String.join("; ", errors));

        return db.inTx(() -> {
            List<String> ids = new ArrayList<>();
            long seq = db.insertEvent(branchId, "IMPORT_SOURCES", null,
                    java.util.Map.of("count", batch.size(),
                            "ids", batch.stream().map(RawSource::id).toList()));
            for (RawSource s : batch) {
                db.insertSource(s.id(), s.kind(), s.version(), s.unit(), s.basis(),
                        s.payloadJson().getBytes(StandardCharsets.UTF_8), "evt@" + seq);
                // source kinds stay separated: RAW_INTENSITY / STATE_ENCODING / DARK_FIELD /
                // REFERENCE_SAMPLE / INSTRUMENT_MATRIX / WAVELENGTH / COVARIANCE
                ids.add(toId(s));
            }
            return ids;
        });
    }

    private static String toId(RawSource s) { return s.id() + "@" + s.version(); }

    private static String validate(RawSource s) {
        try {
            JsonNode n = Database.JSON.readTree(s.payloadJson());
            return switch (s.kind()) {
                case "RAW_INTENSITY" -> n.has("measurements") && n.get("measurements").isArray()
                        ? null : "missing measurements[]";
                case "STATE_ENCODING" -> n.has("states") && n.get("states").isArray()
                        ? null : "missing states[]";
                case "DARK_FIELD" -> n.has("dark") ? null : "missing dark";
                case "REFERENCE_SAMPLE" -> n.has("type") ? null : "missing type";
                case "INSTRUMENT_MATRIX" -> n.has("generator") && n.has("analyzer")
                        ? null : "missing generator/analyzer";
                case "WAVELENGTH" -> n.has("value") ? null : "missing value";
                case "COVARIANCE" -> n.has("type") ? null : "missing type";
                default -> "unknown kind " + s.kind();
            };
        } catch (Exception e) {
            return "unparseable payload: " + e.getMessage();
        }
    }
}
