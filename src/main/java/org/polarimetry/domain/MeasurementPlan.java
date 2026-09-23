package org.polarimetry.domain;

import java.util.List;

/** Ordered (generator, analyzer, rawIntensity) triplets for one material. */
public record MeasurementPlan(List<OpticalState> generators,
                              List<OpticalState> analyzers,
                              double[] rawIntensities) {
    public int measurementCount() { return rawIntensities.length; }
}
