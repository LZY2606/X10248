package polmu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private Json() {}

    public static ObjectNode obj() {
        return M.createObjectNode();
    }

    public static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException("bad json: " + e.getMessage(), e);
        }
    }

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] bytes(Object o) {
        try {
            return M.writeValueAsBytes(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
