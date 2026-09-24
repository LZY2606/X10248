package mmlab.optics;

import java.util.List;
import mmlab.unit.Qty;

/** Immutable, separately versioned source records. Derivation never mutates these. */
public final class SourceRecords {

    private SourceRecords() {
    }

    /** Raw detector intensity for one generator/analyzer pair at one wavelength. */
    public static final class RawIntensity {
        public final String pairId;
        public final String wavelengthId;
        public final Qty intensity;
        public final int replicate;

        public RawIntensity(String pairId, String wavelengthId, Qty intensity, int replicate) {
            this.pairId = pairId;
            this.wavelengthId = wavelengthId;
            this.intensity = intensity;
            this.replicate = replicate;
        }
    }

    /** Dark-frame counts for a wavelength (and optional exposure). */
    public static final class DarkFrame {
        public final String wavelengthId;
        public final Qty level;
        public final Qty sigma;

        public DarkFrame(String wavelengthId, Qty level, Qty sigma) {
            this.wavelengthId = wavelengthId;
            this.level = level;
            this.sigma = sigma;
        }
    }

    /** Reference sample: known Mueller matrix (SI, normalized) and version tag. */
    public static final class ReferenceSample {
        public final String version;
        public final mmlab.math.Mat knownMueller;
        public final String basis;
        public final String note;

        public ReferenceSample(String version, mmlab.math.Mat knownMueller, String basis, String note) {
            this.version = version;
            this.knownMueller = knownMueller;
            this.basis = basis;
            this.note = note;
        }
    }

    /** Instrument matrices W (PSG) and A (PSA), 4x4 each, with their own version. */
    public static final class InstrumentMatrices {
        public final String version;
        public final mmlab.math.Mat psg;
        public final mmlab.math.Mat psa;
        public final String convention;

        public InstrumentMatrices(String version, mmlab.math.Mat psg, mmlab.math.Mat psa,
                                  String convention) {
            this.version = version;
            this.psg = psg;
            this.psa = psa;
            this.convention = convention;
        }
    }

    /** Wavelength point kept as its own source, value+unit preserved. */
    public static final class Wavelength {
        public final String id;
        public final Qty value;

        public Wavelength(String id, Qty value) {
            this.id = id;
            this.value = value;
        }
    }

    /**
     * Covariance matrix for the intensity vector. Independent and correlated
     * variants are stored as distinct versions.
     */
    public static final class CovarianceVersion {
        public final String version;
        public final String kind;
        public final mmlab.math.Mat covariance;
        public final String intensityUnit;
        public final String correlationNote;

        public CovarianceVersion(String version, String kind, mmlab.math.Mat covariance,
                                 String intensityUnit, String correlationNote) {
            this.version = version;
            this.kind = kind;
            this.covariance = covariance;
            this.intensityUnit = intensityUnit;
            this.correlationNote = correlationNote;
        }
    }

    public static final class StateEncoding {
        public final String version;
        public final List<OpticalState> states;
        public final String stokesOrder;
        public final String rotationConvention;

        public StateEncoding(String version, List<OpticalState> states, String stokesOrder,
                             String rotationConvention) {
            this.version = version;
            this.states = states;
            this.stokesOrder = stokesOrder;
            this.rotationConvention = rotationConvention;
        }
    }
}
