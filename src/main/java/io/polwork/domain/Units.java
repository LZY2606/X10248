package io.polwork.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight unit and coordinate-fragment registry for polarimetric quantities.
 * The import layer keeps the raw representation; only derived layers normalize.
 */
public final class Units {

    private Units() {
    }

    public record Quantity(Double value, String rawText, String unit, String basis, String diagnostic) {
        public boolean convertible() {
            return value != null && diagnostic == null;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", value);
            m.put("rawText", rawText);
            m.put("unit", unit);
            m.put("basis", basis);
            m.put("diagnostic", diagnostic);
            return m;
        }
    }

    /** Linear unit conversions supported by the derived layer. */
    public static Double convertToSi(double value, String unit) {
        return switch (unit) {
            case "nm" -> value * 1e-9;
            case "um" -> value * 1e-6;
            case "m" -> value;
            case "deg" -> Math.toRadians(value);
            case "rad" -> value;
            case "count", "counts", "adu", "dimensionless", "" -> value;
            case "s" -> value;
            case "ms" -> value * 1e-3;
            default -> null;
        };
    }

    public static String canonicalUnit(String unit) {
        return switch (unit) {
            case "nm", "um", "m" -> "m";
            case "deg", "rad" -> "rad";
            case "count", "counts", "adu" -> "count";
            default -> unit;
        };
    }

    /** Parse a raw token such as "532 nm", "0.7854 rad", "1200 counts" or "N/A (saturated)". */
    public static Quantity parse(String rawText, String basis) {
        if (rawText == null) {
            return new Quantity(null, null, null, basis, "missing raw value");
        }
        String text = rawText.trim();
        if (text.isEmpty()) {
            return new Quantity(null, rawText, null, basis, "empty raw value");
        }
        String[] parts = text.split("\\s+", 2);
        double value;
        try {
            value = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return new Quantity(null, rawText, parts.length > 1 ? parts[1] : "", basis,
                    "unparseable numeric token: " + parts[0]);
        }
        String unit = parts.length > 1 ? parts[1].trim() : "";
        Double si = convertToSi(value, unit);
        if (si == null) {
            return new Quantity(null, rawText, unit, basis, "unknown unit: " + unit);
        }
        return new Quantity(si, rawText, canonicalUnit(unit), basis, null);
    }

    public static Quantity ofRaw(double value, String unit, String basis) {
        Double si = convertToSi(value, unit);
        if (si == null) {
            return new Quantity(null, value + " " + unit, unit, basis, "unknown unit: " + unit);
        }
        return new Quantity(si, value + " " + unit, canonicalUnit(unit), basis, null);
    }
}
