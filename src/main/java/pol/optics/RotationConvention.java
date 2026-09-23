package pol.optics;

/**
 * Rotation sign convention for polarization angles (fast axis, diattenuation axis).
 * POSITIVE_RH: positive angle rotates Q -> U (right handed about +V propagation).
 * NEGATIVE_LH: the imported file uses the opposite sign. Both are legitimate;
 * the convention must survive import and enter every derived fingerprint.
 */
public enum RotationConvention {
    POSITIVE_RH("+RH", "positive angle Q->U about +V"),
    NEGATIVE_LH("-LH", "positive angle U->Q (opposite sign)");

    private final String code;
    private final String description;

    RotationConvention(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() { return code; }
    public String description() { return description; }

    public double sign() { return this == POSITIVE_RH ? 1.0 : -1.0; }

    public static RotationConvention parse(String raw) {
        if (raw == null || raw.isBlank()) return POSITIVE_RH;
        String s = raw.trim().toLowerCase();
        if (s.contains("neg") || s.contains("lh") || s.startsWith("-")) return NEGATIVE_LH;
        return POSITIVE_RH;
    }
}
