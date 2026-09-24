package mmlab.unit;

/** Conversion failure diagnostic; the value stays at its original position. */
public final class ConvertDiagnostic {
    public final String sourceField;
    public final String fromUnit;
    public final String toUnit;
    public final String message;

    public ConvertDiagnostic(String sourceField, String fromUnit, String toUnit, String message) {
        this.sourceField = sourceField;
        this.fromUnit = fromUnit;
        this.toUnit = toUnit;
        this.message = message;
    }
}
