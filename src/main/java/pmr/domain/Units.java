package pmr.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit handling for domain quantities. Values that cannot be converted stay in
 * their original representation with a diagnostic; derived layers normalize and
 * never silently drop the raw form.
 */
public final class Units {
    private Units() {}

    public record ConvertResult(Double valueNm, String diagnosticCode, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valueNm", valueNm);
            m.put("diagnosticCode", diagnosticCode);
            m.put("message", message);
            return m;
        }
    }

    public static ConvertResult toNanometers(double value, String unit) {
        return switch (unit.trim()) {
            case "nm" -> new ConvertResult(value, null, null);
            case "um", "µm" -> new ConvertResult(value * 1000.0, null, null);
            case "mm" -> new ConvertResult(value * 1_000_000.0, null, null);
            case "angstrom", "\u00c5" -> new ConvertResult(value * 0.1, null, null);
            case "color", "channel-index", "unknown" ->
                    new ConvertResult(null, "UNCONVERTIBLE_UNIT",
                            "value " + value + " expressed in '" + unit
                                    + "' retained in place; no nanometer normalization");
            default -> new ConvertResult(null, "UNKNOWN_UNIT",
                    "unit '" + unit + "' is not on the wavelength conversion table");
        };
    }
}
