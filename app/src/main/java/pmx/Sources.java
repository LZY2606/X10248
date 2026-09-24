package pmx;

import java.util.*;

/**
 * The seven strictly-separated source layers. None of these are mutated after
 * import; reinterpretations create new versions and new branches. Every numeric
 * quantity keeps its raw token, unit and coordinate basis; unconvertible values
 * stay in place with diagnostics rather than being dropped.
 */
public final class Sources {
    private Sources() {}

    public static final List<String> KINDS = List.of(
            "intensity", "state", "dark", "reference", "instrument", "wavelength", "covariance");

    public static final class Layer {
        public String kind;
        public String version;             // semantic version label, e.g. v1
        public String unit;                // e.g. "count", "deg", "nm"
        public String basis;               // coordinate/optical basis label
        public String stokesOrder = "I,Q,U,V";
        public int rotationSign = 1;
        public List<Map<String, String>> rows = new ArrayList<>(); // raw string cells
        public List<String> headers = new ArrayList<>();
        public List<String> diagnostics = new ArrayList<>();
        public String sha;
        public long importedAt;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind); m.put("version", version);
            m.put("unit", unit); m.put("basis", basis);
            m.put("stokesOrder", stokesOrder); m.put("rotationSign", rotationSign);
            m.put("headers", headers); m.put("rows", rows);
            m.put("diagnostics", diagnostics); m.put("sha", sha);
            m.put("importedAt", importedAt);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Layer fromMap(Map<String, Object> m) {
            Layer l = new Layer();
            l.kind = (String) m.get("kind");
            l.version = (String) m.get("version");
            l.unit = (String) m.get("unit");
            l.basis = (String) m.get("basis");
            l.stokesOrder = String.valueOf(m.getOrDefault("stokesOrder", "I,Q,U,V"));
            l.rotationSign = ((Number) m.getOrDefault("rotationSign", 1)).intValue();
            l.headers = (List<String>) m.getOrDefault("headers", new ArrayList<>());
            l.rows = (List<Map<String, String>>) m.getOrDefault("rows", new ArrayList<>());
            l.diagnostics = (List<String>) m.getOrDefault("diagnostics", new ArrayList<>());
            l.sha = (String) m.get("sha");
            l.importedAt = m.get("importedAt") == null ? 0 : ((Number) m.get("importedAt")).longValue();
            return l;
        }
    }

    /** Parse a small RFC-4180-ish CSV with a header row into raw string rows. */
    public static Layer parseCsv(String kind, String version, String unit, String basis,
                                 String csv, int rotationSign, String stokesOrder) {
        Layer l = new Layer();
        l.kind = kind; l.version = version; l.unit = unit; l.basis = basis;
        l.rotationSign = rotationSign; l.stokesOrder = stokesOrder;
        String[] lines = csv.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        boolean first = true;
        for (String line : lines) {
            if (line.isBlank()) continue;
            List<String> cells = parseLine(line);
            if (first) { l.headers = cells; first = false; continue; }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < l.headers.size(); i++)
                row.put(l.headers.get(i), i < cells.size() ? cells.get(i) : "");
            l.rows.add(row);
        }
        return l;
    }

    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else q = !q;
            } else if (c == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** Parse a double without throwing; NaN + diagnostic on failure, raw token kept. */
    public static double num(String raw, List<String> diag) {
        if (raw == null || raw.isBlank()) { diag.add("missing numeric value"); return Double.NaN; }
        try { return Double.parseDouble(raw.trim()); }
        catch (NumberFormatException ex) {
            diag.add("unconvertible numeric '" + raw + "' kept raw, excluded from derived layer");
            return Double.NaN;
        }
    }
}
