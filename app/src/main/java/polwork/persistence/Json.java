package polwork.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared JSON mapper (indentation for human-readable archives/events). */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String s, Class<T> type) {
        try {
            return M.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
