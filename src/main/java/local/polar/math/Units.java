package local.polar.math;

import java.util.Map;

/** SI normalization factors. Wavelength inputs are normalized to metres. */
public final class Units {

    private static final Map<String, Double> FACTORS = Map.ofEntries(
            Map.entry("", 1.0),
            Map.entry("1", 1.0),
            Map.entry("counts", 1.0),
            Map.entry("adu", 1.0),
            Map.entry("W/m^2", 1.0),
            Map.entry("m", 1.0),
            Map.entry("nm", 1e-9),
            Map.entry("um", 1e-6),
            Map.entry("µm", 1e-6),
            Map.entry("angstrom", 1e-10),
            Map.entry("A", 1e-10),
            Map.entry("rad", 1.0),
            Map.entry("deg", Math.PI / 180.0),
            Map.entry("s", 1.0),
            Map.entry("ms", 1e-3)
    );

    private static final Map<String, String> SI_NAMES = Map.of(
            "nm", "m", "um", "m", "µm", "m", "angstrom", "m", "A", "m",
            "deg", "rad", "ms", "s"
    );

    private Units() {
    }

    public static Double toSi(String unit) {
        return FACTORS.get(unit.trim());
    }

    public static String siName(String unit) {
        return SI_NAMES.getOrDefault(unit.trim(), unit.trim());
    }
}
