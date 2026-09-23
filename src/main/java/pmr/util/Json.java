package pmr.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, deterministic JSON implementation for the Polarization Matrix Room.
 * Object key insertion order is preserved so fingerprints stay reproducible.
 */
public final class Json {
    private Json() {
    }

    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    public static List<Object> arr() {
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object value) {
        return (List<Object>) value;
    }

    public static String str(Map<String, Object> root) {
        return write(root, false);
    }

    public static String pretty(Map<String, Object> root) {
        return write(root, true);
    }

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, pretty, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, pretty, indent);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, boolean pretty, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                newline(sb, indent + 1);
            }
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(pretty ? ": " : ":");
            writeValue(sb, entry.getValue(), pretty, indent + 1);
        }
        if (pretty) {
            newline(sb, indent);
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, boolean pretty, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                newline(sb, indent + 1);
            }
            writeValue(sb, item, pretty, indent + 1);
        }
        if (pretty) {
            newline(sb, indent);
        }
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent) {
        sb.append('\n');
        sb.append("  ".repeat(indent));
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWs();
        Object value = parser.readValue();
        parser.skipWs();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Trailing characters at position " + parser.pos);
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) {
            this.text = text;
        }

        private boolean atEnd() {
            return pos >= text.length();
        }

        private void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Object readValue() {
            skipWs();
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = obj();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                pos++;
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char next = nextChar();
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = arr();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char next = nextChar();
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
                }
            }
        }

        private String readString() {
            if (nextChar() != '"') {
                throw new IllegalArgumentException("Expected string at " + pos);
            }
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = nextChar();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = nextChar();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Bad escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Bad literal at " + pos);
        }

        private Object readNull() {
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Bad literal at " + pos);
        }

        private Number readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.valueOf(token);
                }
                long longValue = Long.parseLong(token);
                if (longValue <= Integer.MAX_VALUE && longValue >= Integer.MIN_VALUE) {
                    return (int) longValue;
                }
                return longValue;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Bad number '" + token + "'", ex);
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return text.charAt(pos);
        }

        private char nextChar() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            if (atEnd() || text.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + pos);
            }
        }
    }
}
