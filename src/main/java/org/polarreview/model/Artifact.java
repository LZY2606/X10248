package org.polarreview.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Immutable, versioned record. Source/interpretation are separated: each
 * artifact is either a SOURCE artifact (backed by the exact imported bytes) or
 * a DERIVED artifact (an interpretation referencing its dependencies). Artifacts
 * are never overwritten; a re-interpretation creates a new version on a new
 * branch while the old version, rulings and referenced raw bytes remain
 * readable.
 */
public final class Artifact {
    private static final ObjectMapper M = new ObjectMapper();

    public String uid;
    public String kind;            // raw_intensity_set | state_encoding | dark_frame |
                                   // reference_sample | instrument_matrix | wavelength |
                                   // covariance | estimate | constraint_report | decomposition
    public String materialId;      // logical data lineage ("the same material")
    public int version;
    public String parentUid;       // previous version of same material, nullable
    public String originBlobUid;   // exact raw bytes for sources; null for derived
    public String fingerprint;     // content + convention fingerprint
    public ObjectNode data;
    public String createdAt;

    public Artifact() {}

    public ObjectNode toJson() {
        ObjectNode n = M.createObjectNode();
        n.put("uid", uid);
        n.put("kind", kind);
        n.put("materialId", materialId);
        n.put("version", version);
        if (parentUid != null) n.put("parentUid", parentUid);
        if (originBlobUid != null) n.put("originBlobUid", originBlobUid);
        n.put("fingerprint", fingerprint);
        n.set("data", data);
        n.put("createdAt", createdAt);
        return n;
    }

    public static Artifact fromJson(JsonNode n) {
        Artifact a = new Artifact();
        a.uid = n.get("uid").asText();
        a.kind = n.get("kind").asText();
        a.materialId = n.get("materialId").asText();
        a.version = n.get("version").asInt();
        a.parentUid = n.hasNonNull("parentUid") ? n.get("parentUid").asText() : null;
        a.originBlobUid = n.hasNonNull("originBlobUid") ? n.get("originBlobUid").asText() : null;
        a.fingerprint = n.get("fingerprint").asText();
        a.data = (ObjectNode) n.get("data");
        a.createdAt = n.hasNonNull("createdAt") ? n.get("createdAt").asText() : null;
        return a;
    }
}
