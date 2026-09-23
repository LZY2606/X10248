package pol.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

/** Single shared Jackson mapper with deterministic ordering for stable output. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("json write failed", e);
        }
    }

    public static String pretty(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("json write failed", e);
        }
    }

    public static <T> T read(String s, Class<T> type) {
        try {
            return MAPPER.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("json read failed: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String s, TypeReference<T> type) {
        try {
            return MAPPER.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("json read failed", e);
        }
    }

    public static List<String> readList(String s) {
        try {
            return MAPPER.readValue(s, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
