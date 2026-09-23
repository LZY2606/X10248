package pms.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON tool: canonical serialization preserves insertion order. */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, item);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                writeTo(sb, java.lang.reflect.Array.get(value, i));
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < n(s); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static int n(String s) { return s.length(); }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String text) { return (Map<String, Object>) parse(text); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(String text) { return (List<Object>) parse(text); }

    public static Map<String, Object> newObj() { return new LinkedHashMap<>(); }
    public static List<Object> newArr() { return new ArrayList<>(); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object o) { return (List<Object>) o; }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static double dbl(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v == null) return fallback;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return fallback; }
    }

    public static int integer(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return fallback;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    public static List<Double> dblArr(Object o) {
        List<Double> out = new ArrayList<>();
        for (Object item : (List<Object>) o) out.add(((Number) item).doubleValue());
        return out;
    }

    public static List<Map<String, Object>> objArr(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : asArr(o)) out.add(asObj(item));
        return out;
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }
        void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object readValue() {
            skipWs();
            if (atEnd()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBool();
            if (c == 'n') { expect("null"); return null; }
            return readNumber();
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("expected " + word + " at " + pos);
            pos += word.length();
        }

        Boolean readBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            expect("false");
            return Boolean.FALSE;
        }

        Number readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') { pos++; }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isDouble = true; pos++; }
                else break;
            }
            String token = s.substring(start, pos);
            if (isDouble || token.contains(".")) return Double.parseDouble(token);
            try { return Long.parseLong(token); } catch (NumberFormatException e) { return Double.parseDouble(token); }
        }

        String readString() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                Object v = readValue();
                m.put(key, v);
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                Object v = readValue();
                list.add(v);
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
            }
        }
    }
}
