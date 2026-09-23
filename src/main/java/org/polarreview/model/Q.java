package org.polarreview.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Objects;

/**
 * A measured or derived quantity that always carries its unit / coordinate
 * basis. The original representation (string) is preserved on import; the
 * numeric value is populated only when a conversion exists. Values that cannot
 * be converted stay at their original position with a diagnostic and are never
 * silently dropped for plotting purposes.
 */
public final class Q {
    private static final ObjectMapper M = new ObjectMapper();

    public final String raw;
    public final Double value;
    public final String unit;
    public final String basis;
    public final String diagnostic;

    private Q(String raw, Double value, String unit, String basis, String diagnostic) {
        this.raw = raw;
        this.value = value;
        this.unit = unit;
        this.basis = basis;
        this.diagnostic = diagnostic;
    }

    public static Q numeric(double value, String unit, String basis) {
        return new Q(format(value), value, unit, basis, null);
    }

    public static Q fromRaw(String raw, String unit, String basis) {
        if (raw == null) return new Q(null, null, unit, basis, null);
        String s = raw.trim();
        try {
            return new Q(raw, Double.parseDouble(s), unit, basis, null);
        } catch (NumberFormatException e) {
            return new Q(raw, null, unit, basis,
                    "unconvertible-value: cannot parse '" + raw + "' as " + unit);
        }
    }

    public boolean isUnconvertible() {
        return diagnostic != null;
    }

    private static String format(double v) {
        if (v == 0) return "0";
        double a = Math.abs(v);
        if (a >= 1e6 || a < 1e-4) return String.format(Locale.ROOT, "%.6e", v);
        return String.format(Locale.ROOT, "%.6g", v);
    }

    public ObjectNode toJson() {
        ObjectNode n = M.createObjectNode();
        n.put("raw", raw);
        if (value != null) n.put("value", value);
        if (unit != null) n.put("unit", unit);
        if (basis != null) n.put("basis", basis);
        if (diagnostic != null) n.put("diagnostic", diagnostic);
        return n;
    }

    public static Q fromJson(JsonNode n) {
        if (n == null || n.isNull()) return null;
        Double v = n.hasNonNull("value") ? n.get("value").asDouble() : null;
        return new Q(
                n.hasNonNull("raw") ? n.get("raw").asText() : null,
                v,
                n.hasNonNull("unit") ? n.get("unit").asText() : null,
                n.hasNonNull("basis") ? n.get("basis").asText() : null,
                n.hasNonNull("diagnostic") ? n.get("diagnostic").asText() : null);
    }

    @Override
    public String toString() {
        return raw + (unit != null ? " " + unit : "")
                + (basis != null && !basis.isEmpty() ? " [" + basis + "]" : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Q q)) return false;
        return Objects.equals(raw, q.raw) && Objects.equals(unit, q.unit)
                && Objects.equals(basis, q.basis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, unit, basis);
    }
}
