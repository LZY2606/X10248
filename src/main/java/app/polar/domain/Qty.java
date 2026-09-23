package app.polar.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * A domain quantity that never loses its original representation.
 *
 * @param value        parsed numeric value in the recorded unit (may be null when unconvertible)
 * @param unit         physical unit string (e.g. "count", "rad", "nm", "1", "deg")
 * @param basis        coordinate / reference basis label (e.g. "LAB_Z_LIGHT", "DETECTOR_FACE")
 * @param originalText exactly what the import file carried (verbatim token)
 * @param diagnostic   non-null when the value could not be converted; the quantity stays in place
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Qty(Double value, String unit, String basis, String originalText,
                  String diagnostic) {

    public static Qty of(double value, String unit, String basis) {
        return new Qty(value, unit, basis, null, null);
    }

    public static Qty of(double value, String unit) {
        return of(value, unit, null);
    }

    public static Qty raw(String originalText, String unit, String basis, String diagnostic) {
        return new Qty(null, unit, basis, originalText, diagnostic);
    }

    public boolean isConvertible() {
        return value != null && diagnostic == null;
    }

    /** Normalization happens only on derived layers; failures are diagnosed, never dropped. */
    public Double normalized(String targetUnit) {
        if (value == null) {
            return null;
        }
        return UnitBasis.convert(value, unit, targetUnit);
    }

    @Override
    public String toString() {
        if (value != null) {
            return value + (unit == null || unit.isBlank() || "1".equals(unit) ? "" : " " + unit)
                    + (basis != null ? " [" + basis + "]" : "");
        }
        return "UNCONVERTIBLE(" + originalText + " " + unit
                + (diagnostic != null ? " :: " + diagnostic : "") + ")";
    }
}
