package polwork.domain;

/** Diagnostics of the 16-column measurement design matrix (flattened A). */
public final class DesignInfo {
    public final int rows;
    public final int rank;
    public final double conditionNumber; // sigma_max/sigma_min; +inf when rank deficient
    public final double[] singularValues;
    public final boolean rankDeficient;
    public final double singularThreshold;

    public DesignInfo(int rows, int rank, double conditionNumber, double[] singularValues,
                      boolean rankDeficient, double singularThreshold) {
        this.rows = rows;
        this.rank = rank;
        this.conditionNumber = conditionNumber;
        this.singularValues = singularValues;
        this.rankDeficient = rankDeficient;
        this.singularThreshold = singularThreshold;
    }
}
