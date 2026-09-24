package io.polwork.domain;

import java.util.ArrayList;
import java.util.List;

/** Typed source bundles. Sources are the immutable, separately-versioned inputs. */
public final class SourceBundles {

    private SourceBundles() {
    }

    public record OpticsEncoding(String version, List<Stokes> psg, List<Stokes> psa,
                                 String stokesOrder, String rotationSign, String angleBasis) {
    }

    public record RawReading(String psgId, String psaId, Units.Quantity counts,
                             double exposureSeconds, String note) {
    }

    public record IntensityBundle(String version, List<RawReading> readings, String unit) {
    }

    public record DarkReading(String psaId, Units.Quantity counts) {
    }

    public record DarkBundle(String version, List<DarkReading> readings, String note) {
    }

    public record ReferenceSample(String version, String name, double[][] mueller,
                                  double m00Transmittance, String basis, String note) {
    }

    /** Instrument matrix maps 16 Mueller coefficients to 16 (or N) analyzer channels. */
    public record InstrumentMatrix(String version, double[][] w, String stokesOrder,
                                   String rotationSign, String basis, String note) {
    }

    public record Wavelength(Units.Quantity quantity, String note) {
    }

    public record CovarianceBundle(String version, double[][] matrix, boolean correlated,
                                   String unit, String note) {
    }

    public record Diagnostic(String level, String code, String message, String at) {
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("level", level);
            m.put("code", code);
            m.put("message", message);
            m.put("at", at);
            return m;
        }
    }

    /** A complete, separately-versioned set of the seven source kinds plus diagnostics. */
    public record CaseBundle(String caseId, String label, IntensityBundle intensity,
                             OpticsEncoding optics, DarkBundle dark, ReferenceSample reference,
                             InstrumentMatrix instrument, Wavelength wavelength,
                             CovarianceBundle covariance, List<Diagnostic> diagnostics) {
        public CaseBundle withDiagnostics(List<Diagnostic> extra) {
            List<Diagnostic> all = new ArrayList<>(diagnostics);
            all.addAll(extra);
            return new CaseBundle(caseId, label, intensity, optics, dark, reference, instrument,
                    wavelength, covariance, List.copyOf(all));
        }
    }
}
