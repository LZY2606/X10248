package pol.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the polarimetry raw intensity CSV. It is intentionally
 * domain-specific (generator/analyzer state codes, wavelength, counts) rather
 * than a generic table importer. The original text is always preserved in the
 * blob; this layer only parses, it never rewrites.
 *
 * Header:
 *   measurement_id,generator_code,analyzer_code,wavelength,intensity,note
 */
public final class Measurements {

    public static final class Row {
        public final int index;
        public final String measurementId;
        public final String generatorCode;
        public final String analyzerCode;
        public final Qty wavelength;
        public final Qty intensity;
        public final String rawLine;

        Row(int index, String measurementId, String g, String a, Qty wl, Qty intensity, String rawLine) {
            this.index = index;
            this.measurementId = measurementId;
            this.generatorCode = g;
            this.analyzerCode = a;
            this.wavelength = wl;
            this.intensity = intensity;
            this.rawLine = rawLine;
        }
    }

    public static final class Parse {
        public final List<Row> rows = new ArrayList<>();
        public final List<State.DiagLike> diagnostics = new ArrayList<>();
    }

    private Measurements() {
    }

    public static Parse parse(String text) {
        Parse out = new Parse();
        String[] lines = text.split("\\r?\\n");
        int idx = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li].trim();
            if (line.isEmpty() || (li == 0 && line.toLowerCase().startsWith("measurement_id"))) {
                continue;
            }
            String[] c = line.split(",", -1);
            String location = "line " + (li + 1);
            if (c.length < 5) {
                out.diagnostics.add(new State.DiagLike("malformed-row",
                        "expected 5 columns, found " + c.length, location));
                continue;
            }
            String id = c[0].trim();
            String g = c[1].trim();
            String a = c[2].trim();
            Qty wl = parseDouble(c[3], "nm", location, out);
            Qty intensity = parseDouble(c[4], "count", location, out);
            out.rows.add(new Row(idx++, id, g, a, wl, intensity, line));
        }
        return out;
    }

    private static Qty parseDouble(String token, String unit, String location, Parse out) {
        String t = token.trim();
        try {
            if (t.isBlank() || t.equalsIgnoreCase("NA") || t.equalsIgnoreCase("NaN")) {
                throw new NumberFormatException("blank or NA token");
            }
            double v = Double.parseDouble(t);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException("non-finite");
            }
            return Qty.of(t, unit, v);
        } catch (NumberFormatException e) {
            out.diagnostics.add(new State.DiagLike("unconvertible-value",
                    "value '" + t + "' cannot be converted to " + unit
                            + "; retained in place and excluded from numeric design",
                    location));
            return Qty.rawOnly(t, unit, "not numeric: " + e.getMessage());
        }
    }
}
