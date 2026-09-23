package polwork.domain;

import java.util.List;

/** Dark-field counts per channel; mean and variance kept distinct. */
public final class DarkFrame {
    public final String versionId;
    public final List<Double> meanCounts;
    public final List<Double> varianceCounts;
    public final String unit; // "count"
    public final String note;

    public DarkFrame(String versionId, List<Double> meanCounts,
                     List<Double> varianceCounts, String unit, String note) {
        this.versionId = versionId;
        this.meanCounts = meanCounts;
        this.varianceCounts = varianceCounts;
        this.unit = unit;
        this.note = note;
    }
}
