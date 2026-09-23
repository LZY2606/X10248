package org.polarreview.model;

import java.util.ArrayList;
import java.util.List;

/** Generator and analyzer optical state definitions for one import. */
public final class StateEncoding {
    public final List<OpticalState> generators = new ArrayList<>();
    public final List<OpticalState> analyzers = new ArrayList<>();
    public final List<String> diagnostics = new ArrayList<>();
    public String stokesOrder;
    public String rotationConvention;
}
