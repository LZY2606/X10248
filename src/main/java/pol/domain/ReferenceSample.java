package pol.domain;

import java.util.List;

import org.ejml.data.DMatrixRMaj;

/**
 * A reference sample version used for instrument calibration: its expected
 * normalized Mueller matrix plus the reference intensities used to infer the
 * instrument matrix. Reference revisions create new versions; derived results
 * are invalidated only where their dependency graph points at this version.
 */
public record ReferenceSample(
        String version,
        DMatrixRMaj expectedMueller,
        List<RawMeasurement> referenceIntensities,
        double throughput,
        String unit,
        String basis,
        String note) {
}
