package org.polarroom.store;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static dependency graph between source roles, rules and derived objects.
 *
 * <p>A version change invalidates only the objects downstream of the changed
 * role/rule. The affected set is computed rather than blanket-replacing old
 * conclusions, and old branch states remain readable.
 */
public final class DependencyGraph {

    private DependencyGraph() {
    }

    public static final Map<String, List<String>> DOWNSTREAM = new LinkedHashMap<>();

    static {
        DOWNSTREAM.put("intensity", List.of("design_matrix", "mueller_fit",
                "element_errors", "physical_residuals", "decompositions", "chi_squared"));
        DOWNSTREAM.put("optical_state", List.of("design_matrix", "mueller_fit",
                "element_errors", "physical_residuals", "decompositions"));
        DOWNSTREAM.put("dark", List.of("corrected_signal", "mueller_fit",
                "element_errors", "physical_residuals", "decompositions"));
        DOWNSTREAM.put("reference", List.of("corrected_signal", "mueller_fit",
                "element_errors", "reference_gain_correction"));
        DOWNSTREAM.put("instrument", List.of("calibrated_states", "design_matrix",
                "mueller_fit", "element_errors", "decompositions"));
        DOWNSTREAM.put("wavelength", List.of("measurement_context"));
        DOWNSTREAM.put("covariance", List.of("weighted_fit", "element_errors",
                "chi_squared", "mueller_fit"));
        DOWNSTREAM.put("rule:stokesOrder", List.of("design_matrix", "mueller_fit",
                "fingerprint", "decompositions"));
        DOWNSTREAM.put("rule:rotationSign", List.of("design_matrix", "mueller_fit",
                "fingerprint", "decompositions"));
        DOWNSTREAM.put("rule:rankRelTol", List.of("rank_decision", "mueller_fit",
                "nullspace_candidates"));
        DOWNSTREAM.put("rule:applyReferenceGain", List.of("corrected_signal",
                "mueller_fit"));
        DOWNSTREAM.put("rule:applyInstrumentCalibration", List.of("calibrated_states",
                "mueller_fit"));
    }

    /** Local change example: dark revision must not touch wavelength context. */
    public static Set<String> invalidationScope(String changeKey) {
        Set<String> out = new LinkedHashSet<>();
        collect(changeKey, out);
        return out;
    }

    private static void collect(String key, Set<String> out) {
        List<String> next = DOWNSTREAM.get(key);
        if (next == null) {
            return;
        }
        for (String n : next) {
            if (out.add(n)) {
                collect(n, out);
            }
        }
    }

    /**
     * Produces the machine-readable invalidation event payload attached to a
     * source revision or rule update.
     */
    public static Map<String, Object> invalidationPayload(String changeKey,
                                                          String oldVersion,
                                                          String newVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change", changeKey);
        m.put("oldVersion", oldVersion);
        m.put("newVersion", newVersion);
        m.put("invalidatedObjects", new java.util.ArrayList<>(invalidationScope(changeKey)));
        m.put("policy", "scoped_invalidation_no_global_replace");
        return m;
    }
}
