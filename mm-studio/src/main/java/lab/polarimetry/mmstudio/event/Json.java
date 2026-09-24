package lab.polarimetry.mmstudio.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared Jackson mapper: stable key ordering keeps fingerprints reproducible. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException("json write failed", e); }
    }

    public static String pretty(Object o) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException("json write failed", e); }
    }

    public static <T> T read(String s, Class<T> t) {
        try { return MAPPER.readValue(s, t); }
        catch (Exception e) { throw new RuntimeException("json read failed: " + e.getMessage(), e); }
    }

    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> readMap(String s) {
        return read(s, java.util.Map.class);
    }
}
