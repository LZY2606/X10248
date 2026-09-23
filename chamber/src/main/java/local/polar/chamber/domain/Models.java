package local.polar.chamber.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent domain objects.
 *
 * <p>Every object carries a stable {@code id} (UUID assigned at creation and preserved
 * through export/delete/re-import), a {@code version}, provenance references and, for
 * quantities, a unit or coordinate basis. Raw representations are retained on the
 * source payload; normalization happens only on the derived layer.
 */
public final class Models {

    private Models() {
    }

    public static final String RULES_VERSION = "rules-1.0.0";

    /** The seven separately stored source kinds. Each version is immutable. */
    public enum SourceKind {
        INTENSITIES("intensities", "counts"),
        OPTICAL_STATES("optical_states", "deg"),
        DARK("dark", "counts"),
        REFERENCE("reference", "dimensionless"),
        INSTRUMENT_MATRIX("instrument_matrix", "dimensionless"),
        WAVELENGTH("wavelength", "nm"),
        COVARIANCE("covariance", "counts^2");

        public final String id;
        public final String defaultUnit;

        SourceKind(String id, String defaultUnit) {
            this.id = id;
            this.defaultUnit = defaultUnit;
        }

        public static SourceKind fromId(String s) {
            for (SourceKind k : values()) {
                if (k.id.equals(s)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("unknown source kind: " + s);
        }
    }

    /** One immutable version of one source kind on a branch. */
    public static final class SourceVersion {
        public String id;
        public String branchId;
        public String kind;
        public int version;
        public String unit;
        /** Original representation as imported (numbers, strings, flags) — never normalized here. */
        public String rawJson;
        public String rawSha256;
        public String createdEventId;
        public String createdAt;
        public String note;

        public SourceVersion() {
        }
    }

    /** Append-only audit event. */
    public static final class EventRec {
        public String id;
        public String branchId;
        public int seq;
        public String type;
        public String payloadJson;
        public String parentEventId;
        public String createdAt;

        public EventRec() {
        }
    }

    public static final class BranchRec {
        public String id;
        public String name;
        public String parentBranchId;
        public String createdAt;
        public String purpose;

        public BranchRec() {
        }
    }

    /** A single measurement row: generator/analyzer control angles and observed counts. */
    public static final class Measurement {
        public int index;
        public String generatorStateId;
        public String analyzerStateId;
        public double rawIntensity;
        public String intensityUnit = "counts";

        public Measurement() {
        }
    }

    /** Optical state encoding exactly as imported (degrees, rotation hand convention noted). */
    public static final class OpticalState {
        public String stateId;
        public String role;
        public double angleDeg;
        public String element;
        public String angleUnit = "deg";

        public OpticalState() {
        }
    }

    /** A physical-constraint residual entry. */
    public static final class ConstraintResidual {
        public String name;
        public double value;
        public double tolerance;
        public boolean violated;
        public String detail;

        public ConstraintResidual() {
        }

        public ConstraintResidual(String name, double value, double tolerance, String detail) {
            this.name = name;
            this.value = value;
            this.tolerance = tolerance;
            this.violated = value > tolerance;
            this.detail = detail;
        }
    }

    /** Decomposition candidate derived from an estimated Mueller matrix. */
    public static final class DecompCandidate {
        public String id;
        public String method;
        public double[][] reconstructed;
        public double reconstructionResidual;
        public String summary;
        /** Named factors (e.g. diattenuator/retarder/depolarizer matrices). */
        public List<NamedMatrix> factors = new ArrayList<>();

        public DecompCandidate() {
        }
    }

    public static final class NamedMatrix {
        public String name;
        public double[][] matrix;

        public NamedMatrix() {
        }

        public NamedMatrix(String name, double[][] matrix) {
            this.name = name;
            this.matrix = matrix;
        }
    }

    /** A Mueller candidate produced by an analysis run. */
    public static final class Candidate {
        public String id;
        public String label;
        public double[][] matrixCanonical;
        public double[][] elementCovariance;
        public double[][] elementStdError;
        public double fitResidual;
        public boolean rankDeficient;
        public double designCondition;
        public double designRank;
        public double[] singularValues;
        public double[][] nullSpace;
        public String stokesOrder;
        public String rotationSign;
        public String rulesVersion;
        public String conventionFingerprint;
        public String candidateKind;
        public List<ConstraintResidual> constraintResiduals = new ArrayList<>();
        public boolean physicallyRealizable;
        public List<DecompCandidate> decompositions = new ArrayList<>();
        /** Provenance: source versions consumed. */
        public java.util.Map<String, String> sourceVersionIds = new java.util.LinkedHashMap<>();
        public String note;

        public Candidate() {
        }
    }

    /** Immutable analysis run = one estimation attempt on one branch. */
    public static final class AnalysisRun {
        public String id;
        public String branchId;
        public String createdAt;
        public String conventionFingerprint;
        public String stokesOrder;
        public String rotationSign;
        public String rulesVersion;
        public java.util.Map<String, String> inputs = new java.util.LinkedHashMap<>();
        public List<Candidate> candidates = new ArrayList<>();
        public List<String> diagnostics = new ArrayList<>();
        public String status;

        public AnalysisRun() {
        }
    }

    /** A human adjudication of a candidate. */
    public static final class RulingRec {
        public String id;
        public String branchId;
        public String runId;
        public String candidateId;
        public String decision;
        public String comment;
        public String eventId;
        public String createdAt;
        /** Undo marker: when true the ruling is revoked but the event remains. */
        public boolean revoked;

        public RulingRec() {
        }
    }

    /** Dependency edge stored per analysis run, used to compute invalidation scope. */
    public static final class DependencyEdge {
        public String runId;
        public String sourceKind;
        public String sourceVersionId;
        public String scope;

        public DependencyEdge() {
        }

        public DependencyEdge(String runId, String sourceKind, String sourceVersionId, String scope) {
            this.runId = runId;
            this.sourceKind = sourceKind;
            this.sourceVersionId = sourceVersionId;
            this.scope = scope;
        }
    }

    /** Diagnostics attached while importing: unconvertible values stay in place. */
    public static final class ImportDiagnostic {
        public String path;
        public String severity;
        public String message;
        public String rawValue;

        public ImportDiagnostic() {
        }

        public ImportDiagnostic(String path, String severity, String message, String rawValue) {
            this.path = path;
            this.severity = severity;
            this.message = message;
            this.rawValue = rawValue;
        }
    }
}
