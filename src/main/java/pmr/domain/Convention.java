package pmr.domain;

import java.util.List;
import java.util.Map;

/**
 * Polarization conventions. Stokes order and rotation sign enter every derived
 * fingerprint, so changing either one produces a visibly distinct lineage.
 */
public record Convention(String stokesOrder, String rotationSign) {

    public static final List<String> SUPPORTED_ORDERS = List.of("IQUV", "IUVQ", "IVQU");
    public static final List<String> SUPPORTED_SIGNS = List.of("right-positive", "left-positive");

    public Convention {
        if (!SUPPORTED_ORDERS.contains(stokesOrder)) {
            throw new IllegalArgumentException("unsupported Stokes order: " + stokesOrder);
        }
        if (!SUPPORTED_SIGNS.contains(rotationSign)) {
            throw new IllegalArgumentException("unsupported rotation sign convention: " + rotationSign);
        }
    }

    /** Maps a physical IQUV Stokes component letter to its position under this order. */
    public int index(char component) {
        return stokesOrder.indexOf(component);
    }

    public double sign() {
        return "right-positive".equals(rotationSign) ? 1.0 : -1.0;
    }

    public static Convention fromMap(Map<String, Object> m) {
        return new Convention(String.valueOf(m.get("stokesOrder")),
                String.valueOf(m.get("rotationSign")));
    }
}
