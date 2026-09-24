package polopt.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A provenance-separated source document. The raw representation (original
 * units, original value forms) is preserved verbatim in the payload; the
 * derivation layer is the only layer allowed to normalize it.
 */
public record SourceDoc(
        String id,
        String type,
        String version,
        String unit,
        String basis,
        JsonNode payload) {

    public static final String TYPE_RAW_INTENSITY = "rawIntensity";
    public static final String TYPE_OPTICAL_STATES = "opticalStates";
    public static final String TYPE_DARK = "dark";
    public static final String TYPE_REFERENCE = "reference";
    public static final String TYPE_INSTRUMENT = "instrument";
    public static final String TYPE_WAVELENGTH = "wavelength";
    public static final String TYPE_COVARIANCE = "covariance";
}
