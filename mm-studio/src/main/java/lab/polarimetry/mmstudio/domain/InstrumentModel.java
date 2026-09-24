package lab.polarimetry.mmstudio.domain;

import org.apache.commons.math3.linear.RealMatrix;

/**
 * Instrument matrices (generator/analyzer arm) and reference-sample calibration.
 * The 4x4 matrices act on ideal Stokes vectors in the configured storage order.
 */
public record InstrumentModel(String version, RealMatrix generatorArm, RealMatrix analyzerArm,
                              ReferenceSample reference, double detectorGain,
                              String gainBasis, String note) {
    public record ReferenceSample(String version, RealMatrix knownMueller,
                                  String materialId, String basisNote) {}
}
