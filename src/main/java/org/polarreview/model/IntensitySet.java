package org.polarreview.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One imported raw-intensity table: multiple generator x analyzer conditions.
 * Values keep their original representation plus unit/basis. A value that
 * cannot be converted stays in place with a diagnostic (never dropped for
 * plotting).
 */
public final class IntensitySet {
    public static final class Row {
        public final int generatorIndex;
        public final int analyzerIndex;
        public final Q intensity;
        public final String rawLine;

        public Row(int generatorIndex, int analyzerIndex, Q intensity, String rawLine) {
            this.generatorIndex = generatorIndex;
            this.analyzerIndex = analyzerIndex;
            this.intensity = intensity;
            this.rawLine = rawLine;
        }
    }

    public final List<Row> rows = new ArrayList<>();
    public final List<String> diagnostics = new ArrayList<>();
}
