package polwork.domain;

import java.util.List;

/**
 * Raw intensities (counts) at the Cartesian product of generator/analyzer
 * states for one material/wavelength. Values are immutable source records;
 * interpretation (dark subtraction etc.) lives in the derived layer.
 */
public final class IntensityTable {
    public final String materialId;
    public final String wavelengthId;
    public final List<String> generatorStateNames;
    public final List<String> analyzerStateNames;
    public final double[][] counts; // [g][a]
    public final String unit;

    public IntensityTable(String materialId, String wavelengthId,
                          List<String> generatorStateNames,
                          List<String> analyzerStateNames,
                          double[][] counts, String unit) {
        if (counts.length != generatorStateNames.size()) {
            throw new IllegalArgumentException("generator row count mismatch");
        }
        for (double[] row : counts) {
            if (row.length != analyzerStateNames.size()) {
                throw new IllegalArgumentException("analyzer column count mismatch");
            }
        }
        this.materialId = materialId;
        this.wavelengthId = wavelengthId;
        this.generatorStateNames = generatorStateNames;
        this.analyzerStateNames = analyzerStateNames;
        this.counts = counts;
        this.unit = unit;
    }
}
