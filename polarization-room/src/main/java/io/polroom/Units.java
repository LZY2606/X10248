package io.polroom;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every domain quantity carries its unit or coordinate basis. Imported values keep their
 * raw representation (rawUnit + rawValue); normalization to canonical units happens only
 * in the derived layer. Values that cannot be converted stay at the origin with a
 * diagnostic rather than being silently dropped.
 */
public record Quantity(double rawValue, String rawUnit, String canonicalUnit, Double value, String diagnostic) {

    public static Quantity of(double rawValue, String rawUnit, String canonicalUnit, Double value) {
        return new Quantity(rawValue, rawUnit, canonicalUnit, value, null);
    }

    public static Quantity unconvertible(double rawValue, String rawUnit, String reason) {
        return new Quantity(rawValue, rawUnit, null, null, "unconvertible: " + reason);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rawValue", rawValue);
        m.put("rawUnit", rawUnit);
        m.put("canonicalUnit", canonicalUnit);
        m.put("value", value);
        if (diagnostic != null) m.put("diagnostic", diagnostic);
        return m;
    }
}
