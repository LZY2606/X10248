package lab.polarimetry.mmstudio.domain;

/** Dark counts in detector units; subtracted from raw intensity in the derived layer. */
public record DarkFrame(String version, double meanCounts, double sigmaCounts,
                       String unit, String appliesToStateId, String note) {
}
