package polwork.domain;

/** The seven explicitly separated source/interpretation categories. */
public enum SourceKind {
    INTENSITY("intensity"),
    STATE_ENCODING("state_encoding"),
    DARK("dark"),
    REFERENCE("reference"),
    INSTRUMENT("instrument"),
    WAVELENGTH("wavelength"),
    COVARIANCE("covariance");

    private final String code;

    SourceKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SourceKind fromCode(String code) {
        for (SourceKind k : values()) {
            if (k.code.equalsIgnoreCase(code)) return k;
        }
        throw new IllegalArgumentException("unknown source kind: " + code);
    }
}
