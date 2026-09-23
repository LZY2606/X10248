package pol.casepack;

import pol.domain.Convention;
import pol.domain.PolarStates;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline domain case pack for "Polarization Matrix Room: Mueller measurement,
 * instrument calibration and physical-realizability review". Generates the raw
 * intensity CSV text and the seven separated source layers for two materials,
 * including deliberately pathological rows and singular/nonphysical variants.
 */
public final class CasePack {

    public static final String WAVELENGTH_NM = "632.8";

    /** True (normalized) Mueller matrix for material A: a weak partial polarizer+diattenuator. */
    public static double[][] trueMuellerA() {
        return new double[][] {
                {0.5000, 0.1000, 0.0000, 0.0000},
                {0.0900, 0.4600, 0.0000, 0.0000},
                {0.0000, 0.0000, 0.4200, -0.0300},
                {0.0000, 0.0000, 0.0300, 0.4200}
        };
    }

    /** True Mueller matrix for material B: a depolarizing retarder. */
    public static double[][] trueMuellerB() {
        return new double[][] {
                {1.0000, 0.0000, 0.0000, 0.0000},
                {0.0000, 0.6200, -0.3100, 0.0000},
                {0.0000, 0.3000, 0.6100, 0.0000},
                {0.0000, 0.0000, 0.0000, 0.5800}
        };
    }

    public static final class RawCsv {
        public final String text;
        public final int usableRows;
        public final List<String[]> pathological = new ArrayList<>();

        RawCsv(String text, int usableRows) {
            this.text = text;
            this.usableRows = usableRows;
        }
    }

    /**
     * Full-rank baseline design: 5 generator x 5 analyzer states = 25 rows.
     * Adds a dark-subtractable baseline and one unconvertible row which must be
     * retained with a diagnostic, not dropped silently.
     */
    public static RawCsv baselineCsv(double[][] mueller, String materialPrefix,
                                     double darkCounts, double noiseSigma, java.util.Random rng) {
        String[] gCodes = {"H", "V", "P", "M", "R"};
        String[] aCodes = {"H", "V", "P", "M", "R"};
        Convention conv = Convention.standard();
        PolarStates states = new PolarStates(conv, null, null);

        StringBuilder sb = new StringBuilder();
        sb.append("measurement_id,generator_code,analyzer_code,wavelength,intensity,note\n");
        int usable = 0;
        int mid = 0;
        for (String g : gCodes) {
            for (String a : aCodes) {
                double[] gs = states.generator(g);
                double[] as = states.analyzer(a);
                double normalized = intensity(mueller, gs, as);
                double counts = 1000.0 * normalized + darkCounts + rng.nextGaussian() * noiseSigma;
                mid++;
                sb.append(materialPrefix).append("-M").append(mid)
                        .append(',').append(g).append(',').append(a)
                        .append(',').append(WAVELENGTH_NM)
                        .append(',').append(String.format(java.util.Locale.ROOT, "%.3f", counts))
                        .append(",baseline\n");
                usable++;
            }
        }
        // Pathological but auditable rows: retained verbatim in the blob.
        sb.append(materialPrefix).append("-O1,H,V,").append(WAVELENGTH_NM)
                .append(",N/A,unconvertible intensity retained with diagnostic\n");
        sb.append(materialPrefix).append("-O2,P,M,not-a-number,unconvertible wavelength retained\n");
        return new RawCsv(sb.toString(), usable);
    }

    /**
     * Rank-deficient design: both arms only span the {H,V} subspace. M elements
     * coupling S2/S3 are unobservable, so the estimator must not force an
     * inverse and must keep the null space.
     */
    public static String singularCsv(double[][] mueller) {
        String[] codes = {"H", "V"};
        PolarStates states = new PolarStates(Convention.standard(), null, null);
        StringBuilder sb = new StringBuilder();
        sb.append("measurement_id,generator_code,analyzer_code,wavelength,intensity,note\n");
        int mid = 0;
        for (String g : codes) {
            for (String a : codes) {
                double[] gs = states.generator(g);
                double[] as = states.analyzer(a);
                double counts = 1000.0 * intensity(mueller, gs, as) + 20.0;
                mid++;
                sb.append("S-M").append(mid).append(',').append(g).append(',').append(a)
                        .append(',').append(WAVELENGTH_NM)
                        .append(',').append(String.format(java.util.Locale.ROOT, "%.3f", counts))
                        .append(",singular design only H/V\n");
            }
        }
        return sb.toString();
    }

    /** Corrupt one material-local row; used to prove local-only invalidation. */
    public static String withLocalEdit(String csv, String measurementId, double newCounts, String note) {
        StringBuilder out = new StringBuilder();
        for (String line : csv.split("\\r?\\n", -1)) {
            if (line.startsWith(measurementId + ",")) {
                String[] c = line.split(",", -1);
                if (c.length >= 6) {
                    c[4] = String.format(java.util.Locale.ROOT, "%.3f", newCounts);
                    c[5] = note;
                }
                line = String.join(",", c);
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public static String instrumentMatrixCsv() {
        return "row,col0,col1,col2,col3\n"
                + "0,1,0,0,0\n"
                + "1,0,0.98,0.00,0.00\n"
                + "2,0,0.00,0.97,0.00\n"
                + "3,0,0.00,0.00,0.96\n";
    }

    private static double intensity(double[][] m, double[] g, double[] a) {
        double s = 0;
        for (int i = 0; i < 4; i++) {
            double gi = 0;
            for (int j = 0; j < 4; j++) {
                gi += m[i][j] * g[j];
            }
            s += a[i] * gi;
        }
        return s;
    }

    private CasePack() {
    }
}
