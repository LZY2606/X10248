package lab.polar.polar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A domain quantity as imported (raw representation retained) plus, when possible, a normalized
 * SI value. Values that cannot be converted stay in place with a diagnostic instead of being
 * dropped or silently coerced for plotting.
 */
public record UnitValue(String raw, Double value, String unit, Double siValue, String siUnit,
                        String diagnostic) {

    public static UnitValue parse(String raw, String declaredUnit) {
        if (raw == null) return new UnitValue(null, null, declaredUnit, null, null, "MISSING_RAW_VALUE");
        String text = raw.trim();
        double num;
        try {
            num = Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return new UnitValue(raw, null, declaredUnit, null, null,
                    "UNCONVERTIBLE_VALUE_NOT_NUMERIC: retained in place, excluded from derived layer");
        }
        String u = declaredUnit == null ? "" : declaredUnit.trim().toLowerCase();
        return switch (u) {
            case "nm" -> new UnitValue(raw, num, "nm", num * 1e-9, "m", null);
            case "um", "µm" -> new UnitValue(raw, num, declaredUnit, num * 1e-6, "m", null);
            case "deg", "degree", "°" -> new UnitValue(raw, num, "deg", Math.toRadians(num), "rad", null);
            case "rad" -> new UnitValue(raw, num, "rad", num, "rad", null);
            case "count", "counts", "adu", "" -> new UnitValue(raw, num, u.isEmpty() ? "count" : u, num, "count", null);
            default -> new UnitValue(raw, num, declaredUnit, null, null,
                    "UNCONVERTIBLE_UNIT:" + declaredUnit + ": raw value retained, not normalized");
        };
    }

    public boolean usable() {
        return siValue != null || (value != null && diagnostic == null);
    }

    public Map<String, Object> descriptor() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raw", raw);
        m.put("value", value);
        m.put("unit", unit);
        m.put("siValue", siValue);
        m.put("siUnit", siUnit);
        if (diagnostic != null) m.put("diagnostic", diagnostic);
        return m;
    }
}
