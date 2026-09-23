package polwork.domain;

import java.util.Objects;

/**
 * A scalar with its original representation and unit/coordinate frame.
 * Import preserves {@code rawText} and {@code unit} verbatim; only the derived
 * layer normalizes. A value that cannot be converted stays in place with a
 * diagnostic ({@code converted == null}) rather than being dropped for plots.
 */
public final class Quantity {
    private final Double converted;
    private final String unit;
    private final String rawText;
    private final String frame;
    private final String diagnostic;

    private Quantity(Double converted, String unit, String rawText, String frame, String diagnostic) {
        this.converted = converted;
        this.unit = Objects.requireNonNullElse(unit, "");
        this.rawText = Objects.requireNonNullElse(rawText, "");
        this.frame = frame == null ? "" : frame;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static Quantity of(double valueSIsh, String unit) {
        return new Quantity(valueSIsh, unit, Double.toString(valueSIsh), "", "");
    }

    public static Quantity raw(String rawText, String unit) {
        return new Quantity(null, unit, rawText, "", "unconverted: no normalization rule for unit '" + unit + "'");
    }

    public Quantity withFrame(String f) {
        return new Quantity(converted, unit, rawText, f, diagnostic);
    }

    public Quantity withDiagnostic(String d) {
        return new Quantity(converted, unit, rawText, frame, d);
    }

    /** Normalized numeric value, or null when conversion was impossible. */
    public Double converted() { return converted; }
    public String unit() { return unit; }
    public String rawText() { return rawText; }
    public String frame() { return frame; }
    public String diagnostic() { return diagnostic; }
    public boolean usable() { return converted != null; }
}
