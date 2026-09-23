package polarlab.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit handling: raw representations are preserved in artifacts; conversion
 * happens only in the derived layer. Values that cannot be converted stay in
 * place and produce diagnostics instead of being dropped.
 */
public final class Units {
    private Units() {}

    public record Conversion(double canonicalValue, String canonicalUnit, String diagnostic) {
        public boolean ok() { return diagnostic == null; }
    }

    /** Convert a wavelength to metres. Supports nm, um, mm, m. */
    public static Conversion wavelengthToMetres(String raw) {
        String s = raw.trim().toLowerCase().replaceAll("\\s+", " ");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(nm|um|µm|mm|m|angstrom|a)?")
                .matcher(s);
        if (!m.matches()) {
            return new Conversion(Double.NaN, "m",
                    "cannot parse wavelength '" + raw + "'; raw value kept in place");
        }
        double v = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "nm" : m.group(2);
        return switch (unit) {
            case "nm" -> new Conversion(v * 1e-9, "m", null);
            case "um", "µm" -> new Conversion(v * 1e-6, "m", null);
            case "mm" -> new Conversion(v * 1e-3, "m", null);
            case "m" -> new Conversion(v, "m", null);
            case "angstrom", "a" -> new Conversion(v * 1e-10, "m", null);
            default -> new Conversion(Double.NaN, "m",
                    "unsupported wavelength unit '" + unit + "'; raw value kept in place");
        };
    }

    /** Diagnostic collector used during derivation. */
    public static final class Diagnostics {
        public final List<String> messages = new ArrayList<>();
        public void add(String msg) { messages.add(msg); }
    }
}
