package polroom.domain;

import java.util.List;
import java.util.Map;

/**
 * Stokes ordering and rotation sign conventions.  Conventions are first-class
 * inputs: they are stored per source version and enter derivation fingerprints.
 */
public final class Conventions {

    public enum StokesOrder {
        SHV("S,H,V basis order (I,Q,U,V)", List.of("I", "Q", "U", "V")),
        SIV("S,I,V alternative order", List.of("I", "U", "V", "Q")),
        CUSTOM("custom order carried by data", java.util.List.of());

        private final String description;
        private final List<String> labels;

        StokesOrder(String description, List<String> labels) {
            this.description = description;
            this.labels = labels;
        }

        public List<String> labels() {
            return labels;
        }

        public String description() {
            return description;
        }
    }

    public enum RotationConvention {
        /** Positive theta rotates the x axis toward y, looking into the beam (standard optics). */
        RIGHT_HANDED("theta+ = x -> y, viewed against propagation"),
        /** Mirrored lab bench convention: angle signs are flipped. */
        LEFT_HANDED("theta+ = x -> -y, mirrored bench");

        private final String description;

        RotationConvention(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        public int sign() {
            return this == RIGHT_HANDED ? 1 : -1;
        }
    }

    public static final Map<String, StokesOrder> STOKES_BY_ID = Map.of(
            "SHV", StokesOrder.SHV,
            "SIV", StokesOrder.SIV,
            "CUSTOM", StokesOrder.CUSTOM);

    public static final Map<String, RotationConvention> ROTATION_BY_ID = Map.of(
            "RIGHT_HANDED", RotationConvention.RIGHT_HANDED,
            "LEFT_HANDED", RotationConvention.LEFT_HANDED);

    /**
     * Reorder a 4-component vector into canonical SHV order.
     *
     * @return null when the order is CUSTOM without an explicit label map
     */
    public static double[] toCanonicalShv(double[] s, StokesOrder order, List<String> customLabels) {
        double[] out = new double[4];
        switch (order) {
            case SHV -> {
                System.arraycopy(s, 0, out, 0, 4);
                return out;
            }
            case SIV -> {
                // I,U,V,Q -> I,Q,U,V
                out[0] = s[0];
                out[1] = s[3];
                out[2] = s[1];
                out[3] = s[2];
                return out;
            }
            case CUSTOM -> {
                if (customLabels == null || customLabels.size() != 4) {
                    return null;
                }
                String[] canon = {"I", "Q", "U", "V"};
                for (int i = 0; i < 4; i++) {
                    int idx = customLabels.indexOf(canon[i]);
                    if (idx < 0) {
                        return null;
                    }
                    out[i] = s[idx];
                }
                return out;
            }
            default -> {
                return null;
            }
        }
    }

    private Conventions() {
    }
}
