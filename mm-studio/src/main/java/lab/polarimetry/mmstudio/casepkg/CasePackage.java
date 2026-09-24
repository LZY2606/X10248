package lab.polarimetry.mmstudio.casepkg;

import java.util.List;
import java.util.Map;

/**
 * On-disk/offline domain case package layout (JSON + CSV, bundled under
 * resources/cases/polarization-room):
 *
 *   case.json                       manifest: id, title, materials, versions, replay steps
 *   versions/v01/intensity-*.csv    raw detector intensities (original representation kept)
 *   versions/v01/states.csv         optical state encodings (raw parameters)
 *   versions/v01/dark.json          dark frame
 *   versions/v01/reference.json     reference sample
 *   versions/v01/instrument.json    instrument matrices + detector gain
 *   versions/v01/wavelength.json    wavelength per material
 *   versions/v01/covariance.json    measurement covariance version
 *
 * Each source kind is ingested and versioned separately; replay steps name the
 * single direct source they change.
 */
public record CasePackage(Manifest manifest) {
    public record Manifest(String caseId, String title, String narrative,
                           List<MaterialSpec> materials, List<VersionSpec> versions,
                           List<ReplayStep> replaySteps) {}

    public record MaterialSpec(String materialId, String displayName, String kind,
                               Map<String, String> initialFiles) {}

    public record VersionSpec(String version, String label, String description,
                              String parentVersion,
                              List<SourceFile> changedSources, Map<String, Object> ruleOverrides) {}

    public record SourceFile(String kind, String path, String scopeMaterialId, String note) {}

    /**
     * kind: import_sources | rule_revision | source_revision_local
     *       | reinterpret | verdict | conflict_verdict | parameter_revision
     * changedSource: the single direct source touched (null for decisions)
     */
    public record ReplayStep(String stepId, String title, String kind,
                             String fromVersion, String toVersion,
                             String materialId, String changedSource,
                             Map<String, String> expect) {}
}
