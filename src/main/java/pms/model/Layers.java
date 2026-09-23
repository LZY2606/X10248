package pms.model;

import java.util.List;
import java.util.Map;

/** The seven separately-versioned provenance layers of an import. */
public final class Layers {
    public static final String INTENSITY = "intensity";
    public static final String STATE = "state";
    public static final String DARK = "dark";
    public static final String REFERENCE = "reference";
    public static final String INSTRUMENT = "instrument";
    public static final String WAVELENGTH = "wavelength";
    public static final String COVARIANCE = "covariance";

    public static final List<String> ALL = List.of(
            INTENSITY, STATE, DARK, REFERENCE, INSTRUMENT, WAVELENGTH, COVARIANCE);

    private Layers() {}

    public static boolean isLayer(String name) { return ALL.contains(name); }

    public static Map<String, String> units(String layer) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        switch (layer) {
            case INTENSITY -> m.put("counts", "count");
            case DARK -> m.put("counts", "count");
            case REFERENCE -> m.put("mueller", "dimensionless");
            case INSTRUMENT -> m.put("matrix", "dimensionless");
            case WAVELENGTH -> m.put("value", "nm");
            case COVARIANCE -> m.put("variance", "count^2");
            default -> m.put("value", "");
        }
        return m;
    }
}
