package app.polar.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Identity of a computation configuration. Rotation convention and Stokes
 * ordering are first-class fingerprint inputs: changing either produces a new
 * identity rather than silently overwriting old results.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Fingerprint(String sha256,
                          String stokesOrder,
                          String rotationConvention,
                          String propagationBasis,
                          List<String> orderedInputs,
                          Map<String, String> inputHashes,
                          String rulesetVersion) {

    public String stableKey() {
        return sha256 == null ? "fp:unhashed" : "fp:" + sha256.substring(0, 16);
    }
}
