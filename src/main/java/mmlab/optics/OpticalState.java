package mmlab.optics;

/**
 * One generator or analyzer state: the encoded 4-vector is stored verbatim while
 * the physical element description (kind + angle) records its origin.
 */
public final class OpticalState {
    public final String id;
    public final String role;
    public final String element;
    public final double angleRad;
    public final double[] encoded;
    public final String angleUnit;

    public OpticalState(String id, String role, String element, double angleRad,
                        String angleUnit, double[] encoded) {
        this.id = id;
        this.role = role;
        this.element = element;
        this.angleRad = angleRad;
        this.angleUnit = angleUnit == null ? "rad" : angleUnit;
        this.encoded = encoded;
    }
}
