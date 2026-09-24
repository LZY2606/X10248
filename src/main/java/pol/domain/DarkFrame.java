package pol.domain;

import java.util.List;

/**
 * Dark-frame subtraction version. Values carry detector units and are stored
 * per analyzer channel (or a single scalar when the bundle declares one).
 */
public record DarkFrame(
        String version,
        List<String> tokens,
        List<Double> values,
        String unit,
        String note,
        String basis) {
}
