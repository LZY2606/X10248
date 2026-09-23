package app.polar.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Encoding of one optical state (generator or analyzer position).
 *
 * stokesOrder records the coordinate order used for every vector here, e.g.
 * "IQUV" or "IVUQ"; rotationConvention records the sign convention for rotating
 * element angles, e.g. "CCW_SIGN_POSITIVE_VIEWED_FROM_DETECTOR". Both enter the
 * computation fingerprint, so reordering or flipping signs never silently
 * rewrites a result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateEncoding(String stateId,
                            String kind,
                            Qty[] stokes,
                            Qty angle,
                            String stokesOrder,
                            String rotationConvention,
                            String basis,
                            String note) {

    public double[] stokesValues(String expectedOrder) {
        String actual = stokesOrder == null ? "IQUV" : stokesOrder;
        double[] v = new double[4];
        for (int k = 0; k < 4; k++) {
            char name = actual.charAt(k);
            int target = expectedOrder.indexOf(name);
            if (stokes[k] == null || stokes[k].value() == null) {
                throw new IllegalStateException("state " + stateId + " component " + name
                        + " has no numeric value: " + (stokes[k] == null ? "null" : stokes[k]));
            }
            v[target] = stokes[k].value();
        }
        return v;
    }
}
