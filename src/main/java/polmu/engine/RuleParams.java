package polmu.engine;

import com.fasterxml.jackson.databind.JsonNode;
import polmu.Ids;
import polmu.Json;

import java.util.List;
import java.util.TreeSet;

/**
 * Derivation rule parameters. The rotation sign convention and the Stokes
 * component order are part of the derivation fingerprint, so changing either
 * one produces a new lineage of derived results instead of silently
 * overwriting old conclusions.
 */
public record RuleParams(
        String ruleVersion,
        String rotationConvention,
        List<String> stokesOrder,
        double conditionThreshold
) {
    public static RuleParams defaults() {
        return new RuleParams("rules-v1", "CCW_POSITIVE", List.of("I", "Q", "U", "V"), 1.0e6);
    }

    public static RuleParams fromJson(JsonNode n) {
        List<String> order = Json.M.convertValue(n.get("stokesOrder"),
                Json.M.getTypeFactory().constructCollectionType(List.class, String.class));
        return new RuleParams(
                n.get("ruleVersion").asText(),
                n.get("rotationConvention").asText(),
                order,
                n.get("conditionThreshold").asDouble());
    }

    public JsonNode toJson() {
        return Json.M.valueToTree(this);
    }

    /** Permutation mapping canonical IQUV component index to position in this order. */
    public int[] stokesPermutation() {
        List<String> canonical = List.of("I", "Q", "U", "V");
        int[] perm = new int[4];
        for (int i = 0; i < 4; i++) perm[i] = stokesOrder.indexOf(canonical.get(i));
        return perm;
    }

    /** Rotation angle sign: CCW_POSITIVE keeps the angle, CW_POSITIVE negates it. */
    public double rotationSign() {
        return "CW_POSITIVE".equals(rotationConvention) ? -1.0 : 1.0;
    }

    public String fingerprint(java.util.Collection<String> sourceIds) {
        TreeSet<String> sorted = new TreeSet<>(sourceIds);
        return "fp_" + Ids.sha256(Json.write(toJson()) + "|" + String.join(",", sorted)).substring(0, 32);
    }
}
