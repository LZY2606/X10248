package pol.service;

import pol.domain.Convention;
import pol.domain.Ids;
import pol.domain.Measurements;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports the seven separately-versioned source layers. Original bytes are
 * stored verbatim in content-addressed blobs; interpretation lives in the
 * event payload. The raw representation and units are always retained.
 */
public final class ImportService {

    private final Room room;

    public ImportService(Room room) {
        this.room = room;
    }

    public static final class Imported {
        public final String blobId;
        public final String eventId;
        public final List<Map<String, String>> diagnostics;

        Imported(String blobId, String eventId, List<Map<String, String>> diagnostics) {
            this.blobId = blobId;
            this.eventId = eventId;
            this.diagnostics = diagnostics;
        }
    }

    public Imported importSource(String branch, String sourceKind, String label, String materialId,
                                 String mediaType, byte[] bytes, String version,
                                 String revisionOf, String directSourceId,
                                 Map<String, String> attributes, int expectedHead, String actor) {
        String sha = Ids.sha256(bytes);
        String blobId = Ids.of("blob", sourceKind, materialId == null ? "" : materialId, version, sha);
        String rawText = new String(bytes, StandardCharsets.UTF_8);

        List<Map<String, String>> diagnostics = new ArrayList<>();
        if ("raw_intensities".equals(sourceKind)) {
            Measurements.Parse parsed = Measurements.parse(rawText);
            for (pol.domain.State.DiagLike d : parsed.diagnostics) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("code", d.code);
                m.put("message", d.message);
                m.put("location", d.location);
                diagnostics.add(m);
            }
        }

        Convention convention = new Convention(
                split(attributes.getOrDefault("stokesOrder", "S0,S1,S2,S3")),
                attributes.getOrDefault("rotationSign", "active-positive-CCW"),
                attributes.getOrDefault("angleZero", "analyzer-transmission-axis-at-0deg=horizontal"),
                attributes.getOrDefault("angleUnit", "deg"));
        boolean fingerprintBearing = "state_encoding".equals(sourceKind)
                || "instrument_matrix".equals(sourceKind);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceKind", sourceKind);
        payload.put("label", label);
        payload.put("materialId", materialId);
        payload.put("blobId", blobId);
        payload.put("version", version);
        payload.put("unit", attributes.getOrDefault("unit", defaultUnit(sourceKind)));
        payload.put("coordinateBasis", attributes.getOrDefault("coordinateBasis",
                "Stokes-normalized"));
        if (fingerprintBearing) {
            payload.put("fingerprint", convention.fingerprint());
        }
        payload.put("attributes", attributes);
        payload.put("byteCount", bytes.length);
        payload.put("sha256", sha);
        payload.put("diagnostics", diagnostics);
        if (revisionOf != null) {
            payload.put("supersedes", revisionOf);
        }

        String eventType = revisionOf == null ? "source_imported" : "source_revised";
        var event = room.registerBlobAndAppend(branch, eventType, payload, List.of(), actor,
                expectedHead, directSourceId, revisionOf, blobId, sha, mediaType, bytes, bytes.length);
        return new Imported(blobId, event.id, diagnostics);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            out.add(s.trim());
        }
        return out;
    }

    private static String defaultUnit(String kind) {
        return switch (kind) {
            case "raw_intensities" -> "count";
            case "dark_frame" -> "count";
            case "wavelength" -> "nm";
            case "covariance" -> "count^2";
            case "reference_sample" -> "count/unit-transmittance";
            case "instrument_matrix" -> "1";
            case "state_encoding" -> "1";
            default -> "1";
        };
    }
}
