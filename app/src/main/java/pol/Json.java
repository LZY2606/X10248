package pol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON parser/serializer (preserves insertion order). */
public final class Json {
    private Json() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i < p.s.length()) throw p.error("trailing content");
        return v;
    }

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0, false);
        return sb.toString();
    }

    public static String pretty(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o, int depth, boolean pretty) {
        if (o == null) sb.append("null");
        else if (o instanceof Boolean || o instanceof Number) sb.append(o.toString());
        else if (o instanceof String) writeString(sb, (String) o);
        else if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) sb.append('\n').append(" ".repeat(depth + 2));
                writeString(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), depth + 2, pretty);
            }
            if (pretty) sb.append('\n').append(" ".repeat(depth));
            sb.append('}');
        } else if (o instanceof List) {
            List<?> l = (List<?>) o;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object e : l) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) sb.append('\n').append(" ".repeat(depth + 2));
                write(sb, e, depth + 2, pretty);
            }
            if (pretty) sb.append('\n').append(" ".repeat(depth));
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + o.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- accessor helpers ----
    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new IllegalArgumentException("missing string field: " + key);
        return v.toString();
    }

    public static String strOr(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : v.toString();
    }

    public static double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) return Double.parseDouble(s);
        throw new IllegalArgumentException("missing numeric field: " + key);
    }

    public static double dblOr(Map<String, Object> m, String key, double dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    public static int intOr(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Map)) throw new IllegalArgumentException("missing object field: " + key);
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) throw new IllegalArgumentException("missing array field: " + key);
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objArr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) throw new IllegalArgumentException("missing array field: " + key);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) v) out.add((Map<String, Object>) o);
        return out;
    }

    public static Map<String, Object> newObj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + i + ": " + msg);
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value() {
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') throw error("expected key");
                String key = string();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
                i++; skipWs();
                m.put(key, value());
                skipWs();
                if (i >= s.length()) throw error("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                if (i >= s.length()) throw error("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw error("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw error("bad escape char " + e);
                    }
                } else sb.append(c);
            }
            throw error("unterminated string");
        }

        Boolean bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw error("bad literal");
        }

        Object nul() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw error("bad literal");
        }

        Object number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            String tok = s.substring(start, i);
            try {
                if (tok.contains(".") || tok.contains("e") || tok.contains("E")) return Double.parseDouble(tok);
                long l = Long.parseLong(tok);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException ex) {
                throw error("bad number " + tok);
            }
        }
    }
}
