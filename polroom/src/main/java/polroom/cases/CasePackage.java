package polroom.cases;

import com.fasterxml.jackson.databind.JsonNode;
import polroom.domain.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads the bundled offline Mueller-lab case package from resources/cases. */
public final class CasePackage {

    public final JsonNode manifest;
    public final List<JsonNode> replay;

    private CasePackage(JsonNode manifest, List<JsonNode> replay) {
        this.manifest = manifest;
        this.replay = replay;
    }

    public static CasePackage loadBundled() {
        try (InputStream manifestStream = CasePackage.class.getResourceAsStream("/cases/manifest.json");
             InputStream replayStream = CasePackage.class.getResourceAsStream("/cases/replay.jsonl")) {
            if (manifestStream == null || replayStream == null) {
                throw new IllegalStateException("bundled case package not found");
            }
            JsonNode manifest = Json.MAPPER.readTree(
                    new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8));
            List<JsonNode> replay = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(replayStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        replay.add(Json.MAPPER.readTree(line));
                    }
                }
            }
            return new CasePackage(manifest, replay);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load case package", e);
        }
    }

    public JsonNode scenario(String name) {
        return manifest.path("scenarios").path(name);
    }

    public JsonNode sources() {
        return manifest.path("sources");
    }
}
