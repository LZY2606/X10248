package mmlab.optics;

import java.util.ArrayList;
import java.util.List;
import mmlab.math.Mat;

/**
 * Pairs of generator/analyzer states define the intensity design matrix
 * X[k, 4p+q] = a_p * g_q (row-major Mueller vectorisation). States are decoded
 * from their stored encoding using the declared {@link StokesOrder}.
 */
public final class MeasurementDesign {

    public static final class Pair {
        public final OpticalState generator;
        public final OpticalState analyzer;

        public Pair(OpticalState generator, OpticalState analyzer) {
            this.generator = generator;
            this.analyzer = analyzer;
        }
    }

    private MeasurementDesign() {
    }

    public static Mat buildMatrix(List<Pair> pairs, StokesOrder order) {
        Mat x = new Mat(pairs.size(), 16);
        for (int k = 0; k < pairs.size(); k++) {
            double[] g = order.toCanonical(pairs.get(k).generator.encoded);
            double[] a = order.toCanonical(pairs.get(k).analyzer.encoded);
            for (int p = 0; p < 4; p++) {
                for (int q = 0; q < 4; q++) {
                    x.set(k, 4 * p + q, a[p] * g[q]);
                }
            }
        }
        return x;
    }

    public static List<Pair> grid(List<OpticalState> generators, List<OpticalState> analyzers) {
        List<Pair> pairs = new ArrayList<>();
        for (OpticalState g : generators) {
            for (OpticalState a : analyzers) {
                pairs.add(new Pair(g, a));
            }
        }
        return pairs;
    }
}
