package polroom.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared Jackson mapper configured for stable serialization. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    private Json() {
    }
}
