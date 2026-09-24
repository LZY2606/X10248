package pmx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/** Thin Jackson wrapper so the rest of the code uses plain Map/List objects. */
public final class Json {
    private Json() {}
    public static final ObjectMapper M = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    public static String write(Object o) {
        try { return M.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String s) {
        try { return s == null || s.isBlank() ? new LinkedHashMap<>() : M.readValue(s, Map.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public static List<Object> arr(String s) {
        try { return s == null || s.isBlank() ? new ArrayList<>() : M.readValue(s, List.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public static ObjectNode node() { return M.createObjectNode(); }
}
