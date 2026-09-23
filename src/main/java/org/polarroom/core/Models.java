package org.polarroom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain model shared by storage, engine and web layers.
 *
 * <p>Sources are versioned materials; interpretations are branch-scoped
 * events and derived candidates. Raw bytes are content-addressed so the same
 * bytes always carry the same stable identity across export/delete/reimport.
 */
public final class Models {

    private Models() {
    }

    public enum MaterialType {
        INTENSITY("intensity"),
        OPTICAL_STATE("optical_state"),
        DARK("dark"),
        REFERENCE("reference"),
        INSTRUMENT("instrument"),
        WAVELENGTH("wavelength"),
        COVARIANCE("covariance");

        public final String code;

        MaterialType(String code) {
            this.code = code;
        }

        public static MaterialType fromCode(String c) {
            for (MaterialType t : values()) {
                if (t.code.equals(c)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown material type: " + c);
        }
    }

    /** A value with its physical unit or coordinate basis, as imported. */
    public static final class UnitValue {
        public String raw;
        public double value;
        public String unit;
        public boolean convertible;
        public String diagnostic;

        public UnitValue() {
        }

        public UnitValue(String raw, double value, String unit, boolean convertible,
                         String diagnostic) {
            this.raw = raw;
            this.value = value;
            this.unit = unit;
            this.convertible = convertible;
            this.diagnostic = diagnostic;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("raw", raw);
            m.put("value", convertible ? value : raw);
            m.put("unit", unit);
            m.put("convertible", convertible);
            if (diagnostic != null) {
                m.put("diagnostic", diagnostic);
            }
            return m;
        }

        @SuppressWarnings("unchecked")
        public static UnitValue fromMap(Map<String, Object> m) {
            UnitValue v = new UnitValue();
            v.raw = (String) m.get("raw");
            Object val = m.get("value");
            v.convertible = Boolean.TRUE.equals(m.get("convertible"));
            v.value = val instanceof Number ? ((Number) val).doubleValue() : Double.NaN;
            v.unit = (String) m.get("unit");
            v.diagnostic = (String) m.get("diagnostic");
            return v;
        }
    }

    /** Raw content-addressed bytes. */
    public static final class Blob {
        public String sha256;
        public byte[] content;
        public String mediaType;
    }

    /** One immutable version of a source material. */
    public static final class SourceVersion {
        public String materialId;
        public MaterialType type;
        public int versionNo;
        public String blobSha;
        public String importEventId;
        public String originalFilename;
        public String note;
    }

    /**
     * Append-only branch event.
     *
     * <p>payloadJson is the input snapshot for the decision (rule parameters,
     * selected candidate, accept/reject...). Replaying the prefix of a branch
     * reconstructs exactly the view at that event.
     */
    public static final class Event {
        public String eventId;
        public String branchId;
        public int seq;
        public String parentEventId;
        public String kind;
        public String payloadJson;
        public String inputFingerprint;
        public long createdAt;
        public String authorNote;
        public String revertedOfEventId;
    }

    public static final class Branch {
        public String branchId;
        public String name;
        public String parentBranchId;
        public String baseEventId;
        public String headEventId;
        public long createdAt;
        public String purpose;
    }

    /** Persisted derived candidate/fit (branch-scoped, computed for an event). */
    public static final class DerivedRecord {
        public String derivedId;
        public String branchId;
        public String eventId;
        public String kind;
        public String candidateKey;
        public String fingerprint;
        public String json;
    }

    /** Ruling state as read back at a point in a branch. */
    public static final class RulingState {
        public String subjectKey;
        public String decision;
        public String eventId;
        public String note;
    }

    /** One row of optical-state encoding. */
    public static final class StateRow {
        public String stateId;
        public double azimuthDeg;
        public double ellipticityDeg;
        public String angleUnit;
        public String coordinateBasis;
        public List<String> stokesHeader = new ArrayList<>();
        public List<String> rawStokes = new ArrayList<>();
        public double[] canonicalStokes;
        public List<UnitValue> stokesValues = new ArrayList<>();
    }

    /** One intensity measurement row (a generator/analyser pair). */
    public static final class IntensityRow {
        public int idx;
        public String generatorStateId;
        public String analyserStateId;
        public UnitValue value;
    }
}
