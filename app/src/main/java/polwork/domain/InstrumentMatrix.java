package polwork.domain;

/**
 * Instrument matrices W_g (generators) and W_a (analyzers), 4x4 each, mapping
 * the recorded state encoding into actual polarization states. Calibration is
 * versioned; a revised matrix invalidates only dependent results.
 */
public final class InstrumentMatrix {
    public final String versionId;
    public final double[][] wGenerator;
    public final double[][] wAnalyzer;
    public final String componentBasis;
    public final String note;

    public InstrumentMatrix(String versionId, double[][] wGenerator, double[][] wAnalyzer, String note) {
        this.versionId = versionId;
        this.wGenerator = wGenerator;
        this.wAnalyzer = wAnalyzer;
        this.componentBasis = "instrument-map(Stokes S0,S1,S2,S3), dimensionless";
        this.note = note;
    }
}
