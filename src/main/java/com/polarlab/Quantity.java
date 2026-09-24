package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A domain quantity keeps BOTH its original representation and its normalized SI form.
 * Unconvertible values stay at their origin with a diagnostic instead of being silently dropped.
 */
public final class Quantity {
    public final double rawValue;
    public final String rawUnit;
    public final String frame;        // coordinate/reference basis, e.g. "lab" / "sample"
    public final Double siValue;     // null when conversion is impossible
    public final String siUnit;
    public final String diagnostic;  // non-null when the value could not be normalized

    private Quantity(double rawValue, String rawUnit, String frame, Double siValue, String siUnit, String diagnostic) {
        this.rawValue = rawValue;
        this.rawUnit = rawUnit;
        this.frame = frame;
        this.siValue = siValue;
        this.siUnit = siUnit;
        this.diagnostic = diagnostic;
    }

    public static Quantity raw(double v, String unit, String frame) {
        return new Quantity(v, unit, frame, null, null, "unconverted: normalization layer has not processed this value");
    }

    public static Quantity of(double v, String unit, String frame) {
        Double si = convert(v, unit);
        String d = si == null ? "unknown unit '" + unit + "': value retained at origin, not dropped" : null;
        return new Quantity(v, unit, frame, si, canonicalUnit(unit), d);
    }

    static Double convert(double v, String unit) {
        switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "nm": return v * 1e-9;
            case "um": case "µm": return v * 1e-6;
            case "m": return v;
            case "deg": case "°": return Math.toRadians(v);
            case "rad": return v;
            case "count": case "adu": case "s": return v;
            case "v": return v;
            case "count/s": return v;
            default: return null;
        }
    }

    static String canonicalUnit(String unit) {
        switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "nm": case "um": case "µm": case "m": return "m";
            case "deg": case "°": case "rad": return "rad";
            default: return unit;
        }
    }

    public ObjectNode toJson(ObjectMapper om) {
        ObjectNode n = om.createObjectNode();
        n.put("rawValue", rawValue);
        n.put("rawUnit", rawUnit);
        n.put("frame", frame);
        if (siValue != null) {
            n.put("siValue", siValue);
        }
        n.put("siUnit", siUnit);
        if (diagnostic != null) {
            n.put("diagnostic", diagnostic);
        }
        return n;
    }

    public static Quantity fromJson(JsonNode n) {
        return new Quantity(
                n.path("rawValue").asDouble(),
                n.path("rawUnit").asText(""),
                n.path("frame").asText(""),
                n.hasNonNull("siValue") ? n.get("siValue").asDouble() : null,
                n.path("siUnit").asText(null),
                n.path("diagnostic").asText(null));
    }

    /** List of units understood by the normalization layer (used by import diagnostics/tests). */
    public static List<String> knownUnits() {
        return new ArrayList<>(List.of("nm", "um", "m", "deg", "rad", "count", "adu", "s", "V", "count/s"));
    }
}
