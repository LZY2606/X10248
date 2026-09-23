package polwork.domain;

/**
 * Known reference sample used to anchor calibration, e.g. an air gap
 * (identity Mueller) or a retarder with known Mueller. Stored as 4x4 in
 * physical Stokes order, dimensionless.
 */
public final class ReferenceSample {
    public final String versionId;
    public final String kind;
    public final double[][] muellerKnown;
    public final String componentBasis;
    public final String note;

    public ReferenceSample(String versionId, String kind, double[][] muellerKnown, String note) {
        this.versionId = versionId;
        this.kind = kind;
        this.muellerKnown = muellerKnown;
        this.componentBasis = "Mueller(S0,S1,S2,S3), dimensionless";
        this.note = note;
    }
}
