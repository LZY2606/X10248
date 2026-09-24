package lab.polarimetry.mmstudio.ingest;

/** The seven separated source/interpretation layers. */
public enum SourceKind {
    RAW_INTENSITY("raw_intensity"),
    OPTICAL_STATE("optical_state"),
    DARK_FRAME("dark_frame"),
    REFERENCE_SAMPLE("reference_sample"),
    INSTRUMENT_MATRIX("instrument_matrix"),
    WAVELENGTH("wavelength"),
    COVARIANCE("covariance");

    public final String id;
    SourceKind(String id) { this.id = id; }

    public static SourceKind fromId(String s) {
        for (SourceKind k : values()) if (k.id.equals(s)) return k;
        throw new IllegalArgumentException("unknown source kind: " + s);
    }
}
