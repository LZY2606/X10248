package lab.polarimetry.mmstudio.domain;

import java.util.List;
import java.util.Map;

/** One intensity row: generator state, analyzer state, raw counts, diagnostics. */
public record Measurement(String measurementId, String generatorStateId, String analyzerStateId,
                          double rawIntensity, String intensityUnit,
                          Double wavelengthNm, Map<String, String> rawFields,
                          List<String> diagnostics) {
}
