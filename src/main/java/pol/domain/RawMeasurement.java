package pol.domain;

/**
 * One raw intensity measurement at a (generator, analyzer) pair.
 * The imported token and unit are retained verbatim; parsing failures produce
 * a diagnostic instead of dropping the record.
 */
public record RawMeasurement(
        String id,
        String generatorStateId,
        String analyzerStateId,
        String intensityToken,
        Double intensityValue,
        String unit,
        double timestampSeconds,
        String diagnostic) {

    public boolean usable() {
        return intensityValue != null && (diagnostic == null || diagnostic.isBlank());
    }
}
