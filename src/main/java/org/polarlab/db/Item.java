package org.polarlab.db;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 来源项：原始来源与解释分离。raw 层字节不可变；解释以版本化 JSON 存放。
 * kind ∈ intensity | optic-state | dark | reference | instrument | wavelength | covariance
 */
public record Item(String itemId, int version, String kind, JsonObject json,
                   String rawBytesHash, byte[] rawBytes, String batchId, boolean convertible) {

    private static final Gson GSON = new Gson();

    public String jsonText() { return GSON.toJson(json); }

    public static Item fromRow(String id, int version, String kind, String json,
                               String hash, byte[] raw, String batchId, long convertible) {
        return new Item(id, version, kind, JsonParser.parseString(json).getAsJsonObject(),
                hash, raw, batchId, convertible != 0);
    }
}
