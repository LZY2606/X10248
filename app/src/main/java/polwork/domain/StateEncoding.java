package polwork.domain;

import java.util.List;

/**
 * The optical-state encoding source: PSG/PSA states plus the convention the
 * angles were recorded in. Kept separate from measured intensities.
 */
public final class StateEncoding {
    public final String versionId;
    public final Conventions conventions;
    public final List<OpticalState> generators;
    public final List<OpticalState> analyzers;

    public StateEncoding(String versionId, Conventions conventions,
                         List<OpticalState> generators, List<OpticalState> analyzers) {
        this.versionId = versionId;
        this.conventions = conventions;
        this.generators = generators;
        this.analyzers = analyzers;
    }
}
