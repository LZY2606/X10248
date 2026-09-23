package pol.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    static {
        M.configure(SerializationFeature.INDENT_OUTPUT, true);
    }
    private Json() {}

    public static String write(Object o) {
        try { return M.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static JsonNode read(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new IllegalArgumentException("bad json: " + e.getMessage(), e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String s) {
        return M.convertValue(read(s), Map.class);
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static List<Object> arr(Object... v) {
        List<Object> l = new ArrayList<>();
        for (Object o : v) l.add(o);
        return l;
    }
}
