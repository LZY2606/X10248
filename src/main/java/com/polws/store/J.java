package com.polws.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared Jackson mapper. */
public final class J {
    public static final ObjectMapper M = new ObjectMapper();
    public static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private J() {
    }

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
            throw new IllegalArgumentException("bad json for " + type.getSimpleName(), e);
        }
    }
}
