package polopt.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Material measurement bundle for one sample: design configuration table
 * (generator/analyzer state pairs), raw intensities, dark, covariance and
 * reference inputs, kept separate from their interpretation.
 */
public record ObservationSet(
        String materialId,
        List<Config> configs,
        List<Double> rawIntensity,
        List<Boolean> usable,
        List<String> diagnostics,
        double darkCount,
        double darkUncertainty,
        double[][] covariance,
        double covarianceScale,
        ReferenceModel reference,
        double[][] instrumentMatrix,
        double wavelengthNm,
        String wavelengthBasis,
        String stokesOrder,
        String rotationConvention) {

    public record Config(String configId, String generatorStateId, String analyzerStateId,
                         double[] generatorStokes, double[] analyzerStokes) {
    }

    public record ReferenceModel(String version, double gain, double[] knownStokes, double[][] knownMueller) {
        public static ReferenceModel none() {
            return new ReferenceModel("none", 1.0, null, null);
        }
    }

    /** Design row w = kron(a, g) in the declared Stokes order. */
    public double[][] designMatrix() {
        int n = configs.size();
        double[][] w = new double[n][16];
        for (int k = 0; k < n; k++) {
            double[] g = configs.get(k).generatorStokes();
            double[] a = configs.get(k).analyzerStokes();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    w[k][j * 4 + i] = a[i] * g[j];
                }
            }
        }
        return w;
    }

    /** Dark-subtracted observation vector, excluding unusable (unconvertible) rows. */
    public double[] observationVector() {
        List<Double> ys = new ArrayList<>();
        for (int k = 0; k < configs.size(); k++) {
            if (Boolean.TRUE.equals(usable.get(k))) {
                ys.add(rawIntensity.get(k) - darkCount);
            }
        }
        double[] y = new double[ys.size()];
        for (int k = 0; k < y.length; k++) {
            y[k] = ys.get(k);
        }
        return y;
    }
}
