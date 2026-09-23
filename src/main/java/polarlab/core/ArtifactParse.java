package polarlab.core;

import java.util.ArrayList;
import java.util.List;

/** Parsers for raw artifact bytes. Raw bytes stay stored; parsing happens in the derived layer. */
public final class ArtifactParse {
    private ArtifactParse() {}

    public static final class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
    }

    public static final class States {
        public final List<double[]> generators = new ArrayList<>();
        public final List<double[]> analysers = new ArrayList<>();
    }

    /** CSV: idx,g0,g1,g2,g3,a0,a1,a2,a3 (header line required). */
    public static States states(String csv) {
        States s = new States();
        String[] lines = csv.strip().split("\\R");
        if (lines.length < 2) throw new ParseException("states file needs a header and at least one row");
        if (!lines[0].strip().startsWith("idx,")) throw new ParseException("states header must start with 'idx,'");
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split(",");
            if (p.length != 9) throw new ParseException("states row " + i + " has " + p.length + " columns, expected 9");
            double[] g = new double[4], a = new double[4];
            try {
                for (int k = 0; k < 4; k++) g[k] = Double.parseDouble(p[1 + k].trim());
                for (int k = 0; k < 4; k++) a[k] = Double.parseDouble(p[5 + k].trim());
            } catch (NumberFormatException e) {
                throw new ParseException("states row " + i + " has a non-numeric value: " + e.getMessage());
            }
            s.generators.add(g);
            s.analysers.add(a);
        }
        return s;
    }

    /** CSV: idx,counts -> counts column. */
    public static double[] column(String csv, String what) {
        String[] lines = csv.strip().split("\\R");
        if (lines.length < 2) throw new ParseException(what + " file needs a header and at least one row");
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split(",");
            if (p.length != 2) throw new ParseException(what + " row " + i + " has " + p.length + " columns, expected 2");
            try {
                out.add(Double.parseDouble(p[1].trim()));
            } catch (NumberFormatException e) {
                throw new ParseException(what + " row " + i + " non-numeric: " + e.getMessage());
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** CSV square matrix, no header. */
    public static double[][] matrix(String csv, String what) {
        String[] lines = csv.strip().split("\\R");
        int n = lines.length;
        double[][] m = new double[n][];
        for (int i = 0; i < n; i++) {
            String[] p = lines[i].split(",");
            m[i] = new double[p.length];
            for (int j = 0; j < p.length; j++) {
                try {
                    m[i][j] = Double.parseDouble(p[j].trim());
                } catch (NumberFormatException e) {
                    throw new ParseException(what + " matrix row " + i + " non-numeric: " + e.getMessage());
                }
            }
            if (p.length != n) throw new ParseException(what + " matrix must be square: row " + i
                    + " has " + p.length + " of " + n + " columns");
        }
        return m;
    }

    /** Validate raw bytes for a kind at import time; throws ParseException on failure. */
    public static void validate(String kind, String content) {
        switch (kind) {
            case "STATES" -> states(content);
            case "INTENSITY", "DARK" -> column(content, kind.toLowerCase());
            case "COVARIANCE" -> matrix(content, "covariance");
            case "WAVELENGTH" -> { if (content.isBlank()) throw new ParseException("wavelength is empty"); }
            case "REFERENCE", "INSTRUMENT" -> {
                if (!content.strip().startsWith("{")) throw new ParseException(kind + " must be a JSON object");
            }
            default -> throw new ParseException("unknown artifact kind " + kind);
        }
    }
}
