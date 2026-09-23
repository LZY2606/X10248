package polroom.core;

import java.util.Objects;

/**
 * A value that keeps its original textual representation plus a normalized numeric
 * value in a declared unit / coordinate basis. Values that cannot be converted are
 * retained at their original position with a diagnostic instead of being dropped.
 */
public final class Q {
    public final double value;
    public final String unit;
    public final String basis;
    public final String originalText;
    public final String diagnostic;

    private Q(double value, String unit, String basis, String originalText, String diagnostic) {
        this.value = value;
        this.unit = unit == null ? "" : unit;
        this.basis = basis == null ? "" : basis;
        this.originalText = originalText;
        this.diagnostic = diagnostic;
    }

    public static Q of(double value, String unit, String basis) {
        return new Q(value, unit, basis, null, null);
    }

    public static Q raw(double value, String unit, String basis, String originalText) {
        return new Q(value, unit, basis, originalText, null);
    }

    public static Q unconverted(String originalText, String diagnostic) {
        return new Q(Double.NaN, "", "", originalText, diagnostic);
    }

    public boolean isConvertible() {
        return diagnostic == null && !Double.isNaN(value);
    }

    @Override
    public String toString() {
        if (diagnostic != null) {
            return "UNCONVERTED[" + originalText + ": " + diagnostic + "]";
        }
        String s = String.format(java.util.Locale.ROOT, "%.6g", value);
        return s + (unit.isEmpty() ? "" : " " + unit) + (basis.isEmpty() ? "" : " (" + basis + ")");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Q)) return false;
        Q q = (Q) o;
        return Double.compare(q.value, value) == 0
                && Objects.equals(unit, q.unit) && Objects.equals(basis, q.basis)
                && Objects.equals(originalText, q.originalText) && Objects.equals(diagnostic, q.diagnostic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, unit, basis, originalText, diagnostic);
    }
}
