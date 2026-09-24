package pol.domain;

import org.ejml.data.DMatrixRMaj;

/**
 * Calibrated instrument matrix bundle: analyzer-side W (rows are analyzer
 * Stokes vectors) and generator-side P/G (columns are generator Stokes
 * vectors), with the conventions used to build them.
 */
public record InstrumentMatrix(
        String version,
        DMatrixRMaj analyzerW,
        DMatrixRMaj generatorP,
        StokesOrder stokesOrder,
        RotationConvention rotation,
        String referenceVersion,
        String basis,
        String note) {
}
