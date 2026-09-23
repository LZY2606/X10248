package pmr.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import pmr.util.Hashing;

/**
 * Stable fingerprint of the dependency set + convention + rule version feeding a
 * derivation. Conventions (Stokes order, rotation sign) are first-class inputs.
 */
public record Fingerprint(String ruleVersion, Map<String, Integer> slotVersions,
                          Convention convention, String digest) {

    public static Fingerprint of(String ruleVersion, Map<String, Integer> slotVersions,
                                 Convention convention) {
        Map<String, Integer> sorted = new LinkedHashMap<>(new java.util.TreeMap<>(slotVersions));
        String basis = ruleVersion + "|" + sorted + "|" + convention.stokesOrder()
                + "|" + convention.rotationSign();
        return new Fingerprint(ruleVersion, sorted, convention, Hashing.sha256Hex(basis));
    }
}
