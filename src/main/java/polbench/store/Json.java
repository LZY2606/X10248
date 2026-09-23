package polbench.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {}

    public static String str(Object o) {
        try { return M.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static <T> T read(String s, Class<T> type) {
        try { return M.readValue(s, type); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String arr(List<String> xs) { return str(xs); }

    @SuppressWarnings("unchecked")
    public static List<String> strList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return (List<String>) M.readValue(json, List.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
