package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Stable content fingerprints. Rotation sign convention and Stokes ordering are explicit
 * inputs, so changing either changes the fingerprint instead of being silently absorbed.
 */
public final class Fingerprint {
    private Fingerprint() {}

    static final ObjectMapper ORDERED = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fingerprint of canonical JSON (sorted keys, no whitespace). */
    public static String canonical(JsonNode node) {
        try {
            String json;
            synchronized (ORDERED) {
                json = ORDERED.writeValueAsString(sortKeys(node));
            }
            return sha256Hex(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Object sortKeys(Object o) {
        if (o instanceof JsonNode n) {
            if (n.isObject()) {
                TreeMap<String, Object> map = new TreeMap<>();
                n.fields().forEachRemaining(e -> map.put(e.getKey(), sortKeys(e.getValue())));
                return map;
            }
            if (n.isArray()) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                n.forEach(c -> list.add(sortKeys(c)));
                return list;
            }
            return n.isNull() ? null : ((com.fasterxml.jackson.databind.node.ValueNode) n).asText();
        }
        return o;
    }
}
