package org.polarroom.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the domain-specific offline case pack
 * "polarisation matrix room: Mueller measurement, instrument correction and
 * physical-realisability review".
 *
 * <p>The material is an ideal horizontal linear polariser at 632.8 nm.
 * Forward data are synthesised exactly from the measurement equation so the
 * fit reconstructs the known Mueller matrix to high precision; boundary
 * branches deliberately introduce singular designs, correlated noise and an
 * unphysical realisation.
 */
public final class CaseData {

    public static final String MATERIAL = "horizontal_linear_polariser";
    public static final double WAVELENGTH_NM = 632.8;

    private CaseData() {
    }

    static final class StateDef {
        final String id;
        final double az;
        final double el;

        StateDef(String id, double az, double el) {
            this.id = id;
            this.az = az;
            this.el = el;
        }
    }

    /** Four full-rank states including circular generators/analysers. */
    static List<StateDef> fullRankStates() {
        return List.of(
                new StateDef("H", 0, 0),
                new StateDef("P", 45, 0),
                new StateDef("V", 90, 0),
                new StateDef("R", 0, 45));
    }

    /** Linear-only states span only the S0,S1,S2 subspace: rank 9 design. */
    static List<StateDef> linearOnlyStates() {
        return List.of(
                new StateDef("H", 0, 0),
                new StateDef("P", 45, 0),
                new StateDef("V", 90, 0),
                new StateDef("M", 135, 0));
    }

    public static final class Bundle {
        public String statesCsv;
        public String statesCsvReordered;
        public String intensityCsv;
        public String intensityUnphysicalCsv;
        public String intensityLinearCsv;
        public String darkCsvV1;
        public String darkCsvV2;
        public String referenceCsvV1;
        public String referenceCsvV2;
        public String instrumentV1;
        public String instrumentV2;
        public String wavelengthCsv;
        public String covarianceV1;
        public String covarianceV2;
        public double[][] trueMueller;
    }

    public static Bundle generate() {
        Bundle b = new Bundle();
        b.trueMueller = new double[][]{
                {0.5, 0.5, 0, 0},
                {0.5, 0.5, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0}
        };

        List<StateDef> states = fullRankStates();
        double[][] stokes = new double[states.size()][4];
        for (int i = 0; i < states.size(); i++) {
            stokes[i] = org.polarroom.core.Mueller
                    .stokesState(states.get(i).az, states.get(i).el);
        }

        b.statesCsv = renderStates(states, stokes,
                "S0,S1,S2,S3", 1, "canonical_lab");
        b.statesCsvReordered = renderStates(states, stokes,
                "S0,S2,S1,S3", 1, "canonical_lab");
        b.darkCsvV1 = renderDark(16, 40.0, 1);
        b.darkCsvV2 = renderDark(16, 55.0, 2);
        b.referenceCsvV1 = renderReference(16, 1.0, 1);
        b.referenceCsvV2 = renderReference(16, 0.9, 2);
        b.instrumentV1 = renderInstrument(identityW());
        b.instrumentV2 = renderInstrument(calibratedW());
        b.wavelengthCsv = "field,value\nlambda,632.8 nm\n";
        b.covarianceV1 = renderCovariance(16, 1.0, 0.0, 1);
        b.covarianceV2 = renderCovariance(16, 1.2, 0.25, 2);

        b.intensityCsv = renderIntensity(states, stokes, b, 1, false, false, 1);
        b.intensityUnphysicalCsv =
                renderIntensity(states, stokes, b, 2, false, true, 3);
        b.intensityLinearCsv = renderIntensity(linearOnlyStates(),
                linearStokes(linearOnlyStates()), b, 3, true, false, 4);
        return b;
    }

    private static double[][] linearStokes(List<StateDef> defs) {
        double[][] s = new double[defs.size()][4];
        for (int i = 0; i < defs.size(); i++) {
            s[i] = org.polarroom.core.Mueller
                    .stokesState(defs.get(i).az, defs.get(i).el);
        }
        return s;
    }

    private static double[][] identityW() {
        double[][] w = new double[4][4];
        for (int i = 0; i < 4; i++) {
            w[i][i] = 1.0;
        }
        return w;
    }

    private static double[][] calibratedW() {
        // Small diagonal gain mismatch and S3 rotation residual.
        return new double[][]{
                {1.000, 0, 0, 0},
                {0, 0.992, 0, 0},
                {0, 0, 1.004, 0.003},
                {0, 0, -0.003, 0.996}
        };
    }

    private static String renderStates(List<StateDef> defs, double[][] stokes,
                                       String order, int sign, String basis) {
        StringBuilder sb = new StringBuilder();
        sb.append("# kind=optical_state\n");
        sb.append("# angle_unit=deg\n");
        sb.append("# coordinate_basis=").append(basis).append("\n");
        sb.append("# stokes_order=").append(order).append("\n");
        sb.append("# rotation_sign=").append(sign).append("\n");
        sb.append("# note=generator_and_analyser_state_encoding\n");
        String[] comp = order.split(",");
        sb.append("state_id,azimuth_deg,ellipticity_deg,").append(String.join(",", comp)).append("\n");
        for (int i = 0; i < defs.size(); i++) {
            StateDef d = defs.get(i);
            double[] canon = stokes[i];
            double[] rec = reorderForHeader(canon, order);
            sb.append(d.id).append(',').append(d.az).append(',').append(d.el);
            for (double v : rec) {
                sb.append(',').append(fmt(v));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static double[] reorderForHeader(double[] canon, String order) {
        String[] comp = order.split(",");
        double[] rec = new double[4];
        for (int i = 0; i < 4; i++) {
            int ci = List.of("S0", "S1", "S2", "S3").indexOf(comp[i]);
            rec[i] = canon[ci];
        }
        return rec;
    }

    private static String renderDark(int n, double level, int version) {
        StringBuilder sb = new StringBuilder();
        sb.append("# kind=dark\n");
        sb.append("# version=").append(version).append("\n");
        sb.append("# unit=count\n");
        sb.append("# note=detector_dark_offsets\n");
        sb.append("measurement_id,dark_count\n");
        Lcg rnd = new Lcg(1000 + version);
        for (int i = 1; i <= n; i++) {
            double d = level + (rnd.nextDouble() - 0.5) * 2.0;
            sb.append(i).append(',').append(fmt(d)).append('\n');
        }
        return sb.toString();
    }

    private static String renderReference(int n, double gain, int version) {
        StringBuilder sb = new StringBuilder();
        sb.append("# kind=reference\n");
        sb.append("# version=").append(version).append("\n");
        sb.append("# sample=calibrated_reference_target\n");
        sb.append("# gain_unit=dimensionless\n");
        sb.append("measurement_id,gain,gain_sigma\n");
        Lcg rnd = new Lcg(2000 + version);
        for (int i = 1; i <= n; i++) {
            double g = gain + (rnd.nextDouble() - 0.5) * 0.004;
            sb.append(i).append(',').append(fmt(g)).append(',').append("0.003").append('\n');
        }
        return sb.toString();
    }

    private static String renderInstrument(double[][] w) {
        StringBuilder sb = new StringBuilder();
        sb.append("# kind=instrument\n");
        sb.append("# generator_matrix=W_G analyzer_matrix=W_A\n");
        sb.append("# note=calibration_matrices_applied_to_recorded_states\n");
        sb.append("[W_G generator]\n");
        appendMatrix(sb, w);
        sb.append("[W_A analyzer]\n");
        appendMatrix(sb, transposeLike(w));
        return sb.toString();
    }

    private static void appendMatrix(StringBuilder sb, double[][] w) {
        for (double[] row : w) {
            for (int j = 0; j < 4; j++) {
                sb.append(j > 0 ? "," : "").append(fmt(row[j]));
            }
            sb.append('\n');
        }
    }

    private static double[][] transposeLike(double[][] w) {
        double[][] t = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                t[i][j] = w[j][i];
            }
        }
        return t;
    }

    private static String renderCovariance(int n, double diag, double off, int version) {
        StringBuilder sb = new StringBuilder();
        sb.append("# kind=covariance\n");
        sb.append("# version=").append(version).append("\n");
        sb.append("# unit=count^2\n");
        sb.append("# correlated=").append(off > 0 ? "true" : "false").append("\n");
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double v = i == j ? diag : off * Math.exp(-Math.abs(i - j) / 4.0);
                sb.append(j > 0 ? "," : "").append(fmt(v));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String renderIntensity(List<StateDef> defs, double[][] stokes,
                                          Bundle b, int seed, boolean linear,
                                          boolean unphysical, int version) {
        double[] dark = darkLevels(linear ? 16 : 16,
                version == 3 ? 40 : 40, 1001);
        Lcg rnd = new Lcg(seed * 7919L);
        StringBuilder sb = new StringBuilder();
        sb.append("# kind=intensity\n");
        sb.append("# version=").append(version).append("\n");
        sb.append("# material=").append(MATERIAL).append('\n');
        sb.append("# wavelength_nm=").append(WAVELENGTH_NM).append('\n');
        sb.append("# unit=count\n");
        if (unphysical) {
            sb.append("# note=noise_realisation_driving_unphysical_candidate\n");
        }
        sb.append("measurement_id,generator_state,analyser_state,intensity\n");
        int id = 1;
        for (int gi = 0; gi < defs.size(); gi++) {
            for (int ai = 0; ai < defs.size(); ai++) {
                double[] g = stokes[gi];
                double[] a = stokes[ai];
                double[] mg = org.polarroom.core.LinAlg.multiply(b.trueMueller, g);
                double signal = 0.0;
                for (int k = 0; k < 4; k++) {
                    signal += a[k] * mg[k];
                }
                double scale = 1000.0;
                double noise = (rnd.nextDouble() - 0.5) * (unphysical ? 140 : 6);
                double counts = signal * scale + dark[id - 1] + noise;
                String val = fmt(Math.max(0, counts));
                if (id == 7) {
                    // Keep an unconvertible value in place: never dropped for plots.
                    val = "NA(count)";
                }
                sb.append(id).append(',')
                        .append(defs.get(gi).id).append(',')
                        .append(defs.get(ai).id).append(',')
                        .append(val).append('\n');
                id++;
            }
        }
        return sb.toString();
    }

    private static double[] darkLevels(int n, double level, long seed) {
        Lcg rnd = new Lcg(seed);
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = level + (rnd.nextDouble() - 0.5) * 2.0;
        }
        return d;
    }

    private static String fmt(double v) {
        if (Math.abs(v) < 1e-12) {
            return "0";
        }
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    /** Deterministic LCG so the same case bytes are regenerated on any JDK. */
    static final class Lcg {
        private long state;

        Lcg(long seed) {
            this.state = seed == 0 ? 0x9e3779b97f4a7c15L : seed;
        }

        double nextDouble() {
            state = (state * 6364136223846793005L + 1442695040888963407L)
                    & 0x7fffffffffffffffL;
            return (state >>> 11) / (double) (1L << 52);
        }
    }
}
