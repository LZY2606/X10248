package lab.polar.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser/writer.
 * Values: Map<String,Object> (LinkedHashMap), List<Object>, String, Double, Boolean, null.
 */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean || v instanceof Number) sb.append(v.toString());
        else if (v instanceof String) writeString(sb, (String) v);
        else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void writePretty(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                pad(sb, indent + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(",\n");
                pad(sb, indent + 1);
                writePretty(sb, l.get(i), indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            writeValue(sb, v);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
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

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.eof()) throw new JsonException("Trailing characters at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("Expected JSON object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        Object v = parse(text);
        if (!(v instanceof List)) throw new JsonException("Expected JSON array");
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String key) {
        return (Map<String, Object>) ((Map<String, Object>) o).get(key);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o, String key) {
        Object v = ((Map<String, Object>) o).get(key);
        return v == null ? new ArrayList<>() : (List<Object>) v;
    }

    public static String str(Object o, String key) {
        Object v = ((Map<String, Object>) o).get(key);
        return v == null ? null : v.toString();
    }

    public static double num(Object o, String key, double dflt) {
        Object v = ((Map<String, Object>) o).get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : dflt;
    }

    public static int integer(Object o, String key, int dflt) {
        Object v = ((Map<String, Object>) o).get(key);
        return v instanceof Number ? ((Number) v).intValue() : dflt;
    }

    public static boolean bool(Object o, String key, boolean dflt) {
        Object v = ((Map<String, Object>) o).get(key);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object readValue() {
            skipWs();
            if (eof()) throw new JsonException("Unexpected end of input");
            char c = s.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (peek() != ':') throw new JsonException("Expected ':' at " + pos);
                pos++;
                Object value = readValue();
                m.put(key, value);
                skipWs();
                char ch = peek();
                if (ch == ',') { pos++; continue; }
                if (ch == '}') { pos++; return m; }
                throw new JsonException("Expected ',' or '}' at " + pos);
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                char ch = peek();
                if (ch == ',') { pos++; continue; }
                if (ch == ']') { pos++; return list; }
                throw new JsonException("Expected ',' or ']' at " + pos);
            }
        }

        String readString() {
            if (peek() != '"') throw new JsonException("Expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new JsonException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> throw new JsonException("Bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("Bad literal at " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("Bad literal at " + pos);
        }

        Double readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E'
                    || peek() == '+' || peek() == '-')) {
                pos++;
            }
            String token = s.substring(start, pos);
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException ex) {
                throw new JsonException("Bad number '" + token + "' at " + start);
            }
        }

        char peek() {
            if (eof()) throw new JsonException("Unexpected end of input");
            return s.charAt(pos);
        }
    }
}
