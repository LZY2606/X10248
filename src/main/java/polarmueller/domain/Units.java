package polarmueller.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Unit handling. Raw representations are preserved at import; conversion to a
 * canonical basis happens only in the derived layer. Values that cannot be
 * converted stay in place and produce a diagnostic.
 */
public final class Units {
    private Units() {}

    private static final Map<String, Double> TO_NM = new HashMap<>();
    private static final Map<String, Double> TO_RAD = new HashMap<>();

    static {
        TO_NM.put("nm", 1.0);
        TO_NM.put("um", 1000.0);
        TO_NM.put("angstrom", 0.1);
        TO_RAD.put("rad", 1.0);
        TO_RAD.put("deg", Math.PI / 180.0);
    }

    public static OptionalDouble toNm(double value, String unit) {
        Double f = TO_NM.get(unit);
        return f == null ? OptionalDouble.empty() : OptionalDouble.of(value * f);
    }

    public static OptionalDouble toRad(double value, String unit) {
        Double f = TO_RAD.get(unit);
        return f == null ? OptionalDouble.empty() : OptionalDouble.of(value * f);
    }

    public static boolean knownWavelengthUnit(String unit) { return TO_NM.containsKey(unit); }
    public static boolean knownAngleUnit(String unit) { return TO_RAD.containsKey(unit); }
}
