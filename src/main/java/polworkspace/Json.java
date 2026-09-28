package polworkspace;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static final class Obj extends LinkedHashMap<String, Object> {
        public Obj() {}
        public Obj(String key, Object value) { put(key, value); }
        public Obj str(String key, String value) { put(key, value); return this; }
        public Obj num(String key, Number value) { put(key, value); return this; }
        public Obj bool(String key, boolean value) { put(key, value); return this; }
        public Obj obj(String key, Object value) { put(key, value); return this; }
        public String getStr(String key) { Object v = get(key); return v == null ? null : v.toString(); }
        public double getDouble(String key, double fallback) { Object v = get(key); return v instanceof Number n ? n.doubleValue() : fallback; }
        public int getInt(String key, int fallback) { Object v = get(key); return v instanceof Number n ? n.intValue() : fallback; }
        public boolean getBool(String key, boolean fallback) { Object v = get(key); return v instanceof Boolean b ? b : fallback; }
        @SuppressWarnings("unchecked") public Obj getObj(String key) { return (Obj) get(key); }
        @SuppressWarnings("unchecked") public Arr getArr(String key) { return (Arr) get(key); }
    }

    public static final class Arr extends ArrayList<Object> {
        public Arr() {}
        public Arr(Object... values) { for (Object value : values) add(value); }
        public Arr addObj(Object value) { add(value); return this; }
    }

    public static Obj obj() { return new Obj(); }
    public static Arr arr() { return new Arr(); }
    public static Arr arr(Object... values) { return new Arr(values); }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int indent) {
        if (value == null) out.append("null");
        else if (value instanceof String s) writeString(s, out);
        else if (value instanceof Boolean b) out.append(b.booleanValue());
        else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) out.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append(Long.toString((long) d));
            else out.append(Double.toString(d));
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { out.append("{}"); return; }
            out.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(",\n");
                first = false;
                pad(out, indent + 1);
                writeString(String.valueOf(e.getKey()), out);
                out.append(": ");
                write(e.getValue(), out, indent + 1);
            }
            out.append("\n"); pad(out, indent); out.append("}");
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) { out.append("[]"); return; }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(",\n");
                pad(out, indent + 1);
                write(list.get(i), out, indent + 1);
            }
            out.append("\n"); pad(out, indent); out.append("]");
        } else writeString(String.valueOf(value), out);
    }

    private static void pad(StringBuilder out, int indent) { out.append("  ".repeat(indent)); }
    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    public static Object parse(String text) {
        try {
            Parser parser = new Parser(text);
            Object value = parser.value();
            parser.ws();
            if (!parser.end()) throw new IllegalArgumentException("Trailing JSON at " + parser.pos);
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Obj parseObj(String text) { return (Obj) parse(text); }
    public static Arr parseArr(String text) { return (Arr) parse(text); }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }
        boolean end() { return pos >= s.length(); }
        void ws() { while (!end() && Character.isWhitespace(s.charAt(pos))) pos++; }
        Object value() throws IOException {
            ws();
            if (end()) throw new IOException("Unexpected end");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return number();
        }
        Obj object() throws IOException {
            Obj result = obj(); pos++; ws();
            if (safe() == '}') { pos++; return result; }
            while (true) {
                ws();
                String key = string();
                ws(); expect(':'); pos++;
                Object value = value();
                result.put(key, value);
                ws();
                char c = next();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; break; }
                throw new IOException("Expected , or } at " + pos);
            }
            return result;
        }
        Arr array() throws IOException {
            Arr result = arr(); pos++; ws();
            if (safe() == ']') { pos++; return result; }
            while (true) {
                result.add(value());
                ws();
                char c = next();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; break; }
                throw new IOException("Expected , or ] at " + pos);
            }
            return result;
        }
        String string() throws IOException {
            if (next() != '"') throw new IOException("Expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next(); pos++;
                if (c == '"') break;
                if (c == '\\') {
                    char e = next(); pos++;
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        default -> throw new IOException("Bad escape");
                    }
                    if (e == 'u') pos += 4;
                } else sb.append(c);
            }
            return sb.toString();
        }
        Boolean bool() throws IOException {
            if (s.startsWith("true", pos)) { pos += 4; return true; }
            if (s.startsWith("false", pos)) { pos += 5; return false; }
            throw new IOException("Bad literal");
        }
        Object nul() throws IOException {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new IOException("Bad literal");
        }
        Number number() throws IOException {
            int start = pos;
            if (safe() == '-') pos++;
            while (!end() && "0123456789+-.eE".indexOf(safe()) >= 0) pos++;
            String token = s.substring(start, pos);
            if (token.isBlank()) throw new IOException("Bad number at " + start);
            if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.valueOf(token);
            try { return Long.valueOf(token); } catch (NumberFormatException e) { return Double.valueOf(token); }
        }
        char safe() { return end() ? '\0' : s.charAt(pos); }
        char next() throws IOException { if (end()) throw new IOException("Unexpected end"); return s.charAt(pos); }
        void expect(char c) throws IOException { if (next() != c) throw new IOException("Expected " + c); }
    }
}
