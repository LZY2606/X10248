package com.polarlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain-specific deterministic case data for
 * "偏振矩阵室：Mueller 测量、仪器校正与物理可实现性审查".
 *
 * <p>The narrative: a polarization group imports intensity measurements over many
 * generator/analyzer states, estimates the Mueller matrix, then reviews calibration,
 * decomposition and error propagation.
 *
 * <p>All files are domain objects (intensities, optical state codes, dark frame, reference
 * sample, instrument matrices, wavelength, covariance) — never generic spreadsheets.
 */
public final class CaseData {
    public static final ObjectMapper OM = new ObjectMapper();

    public static final double[][] TRUE_MUELLER = {
            {1.000, 0.150, 0.050, 0.000},
            {0.120, 0.800, 0.020, -0.010},
            {0.030, -0.015, 0.780, 0.030},
            {0.005, 0.012, -0.020, 0.760}
    };

    private CaseData() {}

    // deterministic LCG so the bundled case is reproducible forever
    static final class Rng {
        long s;
        Rng(long seed) { this.s = seed; }
        double next() {
            s = (s * 6364136223846793005L + 1442695040888963407L) >>> 0;
            return (s >>> 11) / (double) (1L << 53);
        }
        double gaussian() {
            double u = Math.max(1e-12, next());
            return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * next());
        }
    }

    public record Optic(String id, String kind, double angleDeg, String waveplate) {}

    public static List<Optic> gridStates(int n) {
        List<Optic> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double a = 180.0 * k / n;
            out.add(new Optic("G" + k, "GENERATOR", a, "linear"));
            out.add(new Optic("A" + k, "ANALYZER", a, "linear"));
        }
        // add QWP states for circular sensitivity
        for (int k = 0; k < 2; k++) {
            out.add(new Optic("GQ" + k, "GENERATOR", 45 * k, "QWP"));
            out.add(new Optic("AQ" + k, "ANALYZER", 45 * k, "QWP"));
        }
        return out;
    }

    public static ObjectNode statesDoc(String order, String rotSign, List<Optic> optics) {
        ObjectNode n = OM.createObjectNode();
        n.put("order", order);
        n.put("rotSign", rotSign);
        ArrayNode st = n.putArray("states");
        for (Optic o : optics) {
            ObjectNode x = st.addObject();
            x.put("id", o.id());
            x.put("kind", o.kind());
            x.put("angleDeg", o.angleDeg());
            x.put("waveplate", o.waveplate());
        }
        return n;
    }

    public record Cell(String id, String generator, String analyzer, double raw, double var) {}

    public static List<Cell> synthesize(List<Optic> optics, double dark, double gain,
                                        double noiseSigma, long seed, boolean singular) {
        List<Optic> gens = optics.stream().filter(o -> o.kind.equals("GENERATOR")).toList();
        List<Optic> anas = optics.stream().filter(o -> o.kind.equals("ANALYZER")).toList();
        Rng rng = new Rng(seed);
        List<Cell> cells = new ArrayList<>();
        double[] mv = new double[16];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(TRUE_MUELLER[i], 0, mv, 4 * i, 4);
        }
        int idx = 0;
        int n = singular ? 8 : gens.size();
        int na = singular ? 2 : anas.size();
        for (int ig = 0; ig < n; ig++) {
            Optic g = gens.get(ig);
            for (int ia = 0; ia < na; ia++) {
                Optic a = anas.get(ia % anas.size());
                double[] gs = stateOf(g, PolarState.Order.S0123, PolarState.RotSign.POS_CCW);
                double[] as = stateOf(a, PolarState.Order.S0123, PolarState.RotSign.POS_CCW);
                double ideal = Mueller.predict(as, gs, mv) * gain + dark;
                double v = ideal + rng.gaussian() * noiseSigma;
                cells.add(new Cell("I" + idx++, g.id, a.id, v, noiseSigma * noiseSigma));
            }
        }
        if (singular) {
            // append rows that duplicate an existing (analyzer,generator) row -> rank stays 15
            Cell template = cells.get(3);
            for (int k = 0; k < 16; k++) {
                double v = template.raw() + rng.gaussian() * noiseSigma;
                cells.add(new Cell("I" + idx++, template.generator(), template.analyzer(), v, noiseSigma * noiseSigma));
            }
        }
        return cells;
    }

    private static double[] stateOf(Optic o, PolarState.Order order, PolarState.RotSign sign) {
        PolarState ps = "QWP".equals(o.waveplate())
                ? PolarState.quarterWave(o.angleDeg(), order, sign, o.id())
                : PolarState.linear(o.angleDeg(), order, sign, o.id());
        return ps.canonical();
    }

    public static ObjectNode intensityDoc(List<Cell> cells) {
        ObjectNode n = OM.createObjectNode();
        ArrayNode rows = n.putArray("rows");
        for (Cell c : cells) {
            ObjectNode r = rows.addObject();
            r.put("id", c.id());
            r.put("generatorId", c.generator());
            r.put("analyzerId", c.analyzer());
            ObjectNode val = r.putObject("value");
            val.put("rawValue", round(c.raw()));
            val.put("rawUnit", "count");
            val.put("frame", "detector");
            r.put("variance", round(c.var()));
        }
        return n;
    }

    public static ObjectNode darkDoc(double value, double variance) {
        ObjectNode n = OM.createObjectNode();
        ObjectNode v = n.putObject("value");
        v.put("rawValue", round(value));
        v.put("rawUnit", "count");
        v.put("siValue", round(value));
        v.put("siUnit", "count");
        v.put("frame", "detector");
        n.put("variance", round(variance));
        n.put("version", "dark-v1");
        return n;
    }

    public static ObjectNode referenceDoc(double gain, double gainRelVar, String version) {
        ObjectNode n = OM.createObjectNode();
        n.put("sampleId", "REF-AIR-CALIBRATED-POLARIZER");
        n.put("version", version);
        n.put("gain", gain);
        n.put("gainRelVar", gainRelVar);
        ObjectNode note = n.putObject("note");
        note.put("meaning", "reference intensity normalization gain");
        note.put("frame", "lab");
        return n;
    }

    public static ObjectNode wavelengthDoc(double nm) {
        ObjectNode n = OM.createObjectNode();
        ObjectNode v = n.putObject("value");
        v.put("rawValue", nm);
        v.put("rawUnit", "nm");
        v.put("siValue", nm * 1e-9);
        v.put("siUnit", "m");
        v.put("frame", "vacuum");
        return n;
    }

    public static ObjectNode covarianceDoc(List<Cell> cells, boolean correlated, double rho) {
        ObjectNode n = OM.createObjectNode();
        n.put("version", "cov-v1");
        n.put("correlated", correlated);
        n.put("rho", rho);
        ArrayNode m = n.putArray("matrix");
        int k = cells.size();
        for (int i = 0; i < k; i++) {
            ArrayNode row = m.addArray();
            for (int j = 0; j < k; j++) {
                double v = i == j ? cells.get(i).var()
                        : (correlated ? rho * Math.sqrt(cells.get(i).var() * cells.get(j).var()) : 0);
                row.add(round(v));
            }
        }
        return n;
    }

    public static ObjectNode instrumentDoc(String which) {
        ObjectNode n = OM.createObjectNode();
        n.put("order", "S0123");
        n.put("rotSign", "POS_CCW");
        n.put("device", which);
        ArrayNode rows = n.putArray("rows");
        double[][] ideal = Mueller.identity4();
        for (double[] r : ideal) {
            ArrayNode row = rows.addArray();
            for (double v : r) {
                row.add(v);
            }
        }
        return n;
    }

    /** Perturb one measurement enough to make the fitted Mueller non-physical (flag, no projection). */
    public static ObjectNode injectNonphysical(ObjectNode intensityDoc, long seed) {
        ObjectNode copy = intensityDoc.deepCopy();
        Rng rng = new Rng(seed);
        ArrayNode rows = (ArrayNode) copy.get("rows");
        // corrupt a handful of rows strongly and inconsistently
        for (int k = 0; k < rows.size(); k += 7) {
            ObjectNode r = (ObjectNode) rows.get(k);
            double v = r.get("value").get("rawValue").asDouble();
            double bad = v + (rng.next() > 0.5 ? 1 : -1) * (5 + 3 * rng.next()) * Math.sqrt(r.get("variance").asDouble());
            ((ObjectNode) r.get("value")).put("rawValue", round(bad));
        }
        return copy;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
