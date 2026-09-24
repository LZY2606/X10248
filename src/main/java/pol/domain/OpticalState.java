package pol.domain;

/**
 * One generator or analyzer polarization state.
 *
 * @param id       stable source identifier
 * @param role     "generator" or "analyzer"
 * @param stokes   4-vector in the order declared by {@link StokesOrder}
 * @param angleDeg original rotation angle token as imported (kept verbatim)
 * @param basis    coordinate/reference basis label (e.g. lab H/V axes)
 * @param unit     unit of the angle token ("deg"/"rad")
 */
public record OpticalState(
        String id,
        String role,
        double[] stokes,
        String angleToken,
        Double angleRad,
        String basis,
        String unit,
        String diagnostic) {

    public boolean convertible() {
        return angleRad != null;
    }
}
