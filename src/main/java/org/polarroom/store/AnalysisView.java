package org.polarroom.store;

import org.polarroom.core.Models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view reconstructed by replaying a branch prefix.
 *
 * <p>The view binds logical material roles to concrete source versions via the
 * most recent {@code role_binding} events on the prefix, so time travel
 * restores the versions that were in force at an old decision.
 */
public final class AnalysisView {

    public final String branchId;
    public final String atEventId;
    public final List<Models.Event> prefix;
    public final Map<String, Integer> versionByRole = new LinkedHashMap<>();
    public final Map<String, Object> ruleParams = new LinkedHashMap<>();
    public final String ruleVersion;
    public final String stokesOrder;
    public final int rotationSign;
    public final Map<String, Models.RulingState> rulings;

    public AnalysisView(String branchId, String atEventId, List<Models.Event> prefix,
                        Map<String, Integer> versionByRole, Map<String, Object> ruleParams,
                        String ruleVersion, String stokesOrder, int rotationSign,
                        Map<String, Models.RulingState> rulings) {
        this.branchId = branchId;
        this.atEventId = atEventId;
        this.prefix = prefix;
        this.versionByRole.putAll(versionByRole);
        this.ruleParams.putAll(ruleParams);
        this.ruleVersion = ruleVersion;
        this.stokesOrder = stokesOrder;
        this.rotationSign = rotationSign;
        this.rulings = rulings;
    }

    public Integer versionOf(String role) {
        return versionByRole.get(role);
    }
}
