package pol.decomp;

import java.util.List;

import org.ejml.data.DMatrixRMaj;

/**
 * One decomposition candidate. Multiple candidates are always retained: when
 * the data cannot uniquely determine a decomposition (e.g. non-physical
 * Mueller matrix), every legal candidate is stored and the reviewer decides.
 */
public record Decomposition(
        String id,
        String name,
        String convention,
        DMatrixRMaj m,
        DMatrixRMaj reconstructed,
        double relativeResidual,
        List<Part> parts,
        List<Metric> metrics,
        boolean valid,
        String note) {

    public record Part(String role, String label, DMatrixRMaj matrix) {
    }

    public record Metric(String name, double value, String unit) {
    }
}
