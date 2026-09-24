package polar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Core domain records. Every value carries a stable identity. */
public final class Model {
    public static final ObjectMapper JSON = new ObjectMapper();

    private Model() {}

    /** Source kinds, each versioned and stored separately (raw bytes preserved). */
    public enum SourceKind {
        INTENSITIES("intensities"),
        STATES("states"),
        DARK("dark"),
        REFERENCE("reference"),
        INSTRUMENT("instrument"),
        WAVELENGTH("wavelength"),
        COVARIANCE("covariance");
        public final String wire;
        SourceKind(String wire) { this.wire = wire; }
        public static SourceKind of(String wire) {
            for (SourceKind k : values()) if (k.wire.equals(wire)) return k;
            throw new IllegalArgumentException("unknown source kind: " + wire);
        }
    }

    /** A versioned source object. Raw representation is preserved verbatim. */
    public record Source(String id, SourceKind kind, int version, byte[] content,
                         String mediaType, String metaJson) {}

    /** Event types; every judgment is a machine-readable event. */
    public enum EventType {
        IMPORT_SOURCES, SOURCE_REVISION, PARAM_REVISION, DERIVE,
        DECISION_ACCEPT, DECISION_REJECT, REVERT, BRANCH_CREATE, MIGRATE
    }

    public record Event(String id, String branch, long seq, String parentId,
                        EventType type, String payloadJson, String fingerprint, long ts) {}

    public record Branch(String name, String headEvent, long version) {}

    /** Derived object kinds produced by derivation rules. */
    public enum DerivedKind { DESIGN, MUELLER, PHYSICAL, DECOMPOSITION }

    public record Derived(String id, String branch, DerivedKind kind, String createdByEvent,
                          List<String> sourceDeps, List<String> derivedDeps,
                          String payloadJson, String status, String fingerprint) {}

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static ObjectNode obj() { return JSON.createObjectNode(); }

    public static JsonNode parse(String json) {
        try { return JSON.readTree(json); }
        catch (Exception e) { throw new RuntimeException("bad json: " + e.getMessage(), e); }
    }

    public static String write(JsonNode n) {
        try { return JSON.writeValueAsString(n); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
