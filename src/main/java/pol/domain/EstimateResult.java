package pol.domain;

import java.util.List;

import org.ejml.data.DMatrixRMaj;

/**
 * Estimator output for one wavelength and fingerprint.
 *
 * When the design matrix is rank deficient the estimator does not pretend a
 * unique answer: {@code status} is RANK_DEFICIENT, all null-space directions
 * are retained, and each legal candidate is enumerated for the reviewer.
 */
public record EstimateResult(
        String fingerprint,
        String status,
        DMatrixRMaj mueller,
        DMatrixRMaj covariance,
        double[] elementSigma,
        Fit fit,
        int measurementCount,
        int expectedParameters,
        List<DMatrixRMaj> nullspaceDirections,
        List<DMatrixRMaj> candidates,
        String correctionPath,
        List<String> notes) {

    public boolean unique() {
        return "OK".equals(status) || "NEAR_SINGULAR".equals(status);
    }
}
