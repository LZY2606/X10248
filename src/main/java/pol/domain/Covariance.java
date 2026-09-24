package pol.domain;

import org.ejml.data.DMatrixRMaj;

/**
 * Measurement covariance version for the intensity vector y (diagonal or
 * correlated). Correlated versions change error propagation (GLS whitening);
 * a covariance change invalidates uncertainties, not the raw intensities.
 */
public record Covariance(
        String version,
        DMatrixRMaj matrix,
        String unit,
        boolean correlated,
        String basis,
        String note) {
}
