package pol.service;

import pol.domain.Qty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable projection of an event prefix. Replaying a branch up to sequence N
 * yields exactly the state visible when N was the head, so historical views do
 * not get overwritten by newer calculations.
 */
public final class State {

    public static final class Blob {
        public String id;
        public String sha256;
        public String mediaType;
        public long byteCount;
        public String importedAt;
    }

    public static final class Diagnostic {
        public String code;
        public String message;
        public String location;
    }

    public static final class Source {
        public String id;
        public String kind;
        public String label;
        public String materialId;
        public String blobId;
        public String version;
        public String revisionOfId;
        public String directSourceId;
        public String createdAt;
        public String actor;
        public String unit;
        public String coordinateBasis;
        public String conventionFingerprint;
        public Map<String, String> attributes = new LinkedHashMap<>();
        public List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        public boolean active = true;
    }

    public static final class Estimate {
        public String id;
        public String materialId;
        public String seq;
        public String label;
        public String conventionFingerprint;
        public String stokesOrder;
        public String rotationSign;
        public double conditionNumber;
        public double[] singularValues;
        public int rank;
        public int nullity;
        public boolean unique;
        public boolean nearSingular;
        public double designThreshold;
        public double residualNorm;
        public double[][] mueller;
        public double[][] elementSigma;
        public double[][] nullspace;
        public List<double[][]> legalFamily;
        public List<String> sourceIds = new java.util.ArrayList<>();
        public String decision = "unreviewed";
        public String decisionEventId;
        public String decisionNote;
        public String decisionAt;
        public boolean stale;
        public String staleReason;
        public String createdEventId;
        public String createdAt;
    }

    public static final class Ruling {
        public String eventId;
        public String estimateId;
        public String decision;
        public String note;
        public String at;
        public String actor;
        public boolean reverted;
    }

    public String branch;
    public int headSeq;
    public String headEventId;
    public Map<String, Blob> blobs = new LinkedHashMap<>();
    public Map<String, Source> sources = new LinkedHashMap<>();
    public Map<String, Estimate> estimates = new LinkedHashMap<>();
    public List<Ruling> rulings = new java.util.ArrayList<>();
    public List<Map<String, Object>> timeline = new java.util.ArrayList<>();
    public Map<String, String> notes = new LinkedHashMap<>();
}
