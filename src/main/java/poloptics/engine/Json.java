package poloptics.engine;

/** Tiny dependency-free JSON writer with escaping. */
public final class Json {
    private Json() {}
    private final static String HEX = "0123456789abcdef";

    public static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append("\\u").append(pad4(Integer.toHexString(c)));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String pad4(String h) {
        StringBuilder sb = new StringBuilder();
        for (int i = h.length(); i < 4; i++) sb.append('0');
        return sb.append(h).toString();
    }

    public static String num(double v) {
        if (Double.isNaN(v)) return "null";
        if (Double.isInfinite(v)) return v > 0 ? "1e999" : "-1e999";
        if (v == 0) return "0";
        double a = Math.abs(v);
        if (a != 0 && (a < 1e-4 || a >= 1e7)) return String.format(java.util.Locale.ROOT, "%.6e", v);
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }

    public static String vec(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i>0) sb.append(','); sb.append(num(v[i])); }
        return sb.append(']').toString();
    }

    public static String mat(double[][] m) {
        if (m == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < m.length; i++) {
            if (i>0) sb.append(',');
            sb.append('[');
            for (int j = 0; j < m[i].length; j++) { if (j>0) sb.append(','); sb.append(num(m[i][j])); }
            sb.append(']');
        }
        return sb.append(']').toString();
    }
}
