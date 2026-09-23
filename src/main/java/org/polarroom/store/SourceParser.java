package org.polarroom.store;

import org.polarroom.core.Conventions;
import org.polarroom.core.Models;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the offline case file formats.
 *
 * <p>Files are small, line-oriented CSV with {@code # key=value} metadata
 * headers. Parsing never silently repairs data: unconvertible values stay in
 * place as {@link org.polarroom.core.Models.UnitValue} entries carrying
 * diagnostics, and the original bytes remain available via the blob store.
 */
public final class SourceParser {

    private SourceParser() {
    }

    public static final class Parsed {
        public Models.MaterialType type;
        public Map<String, String> metadata = new LinkedHashMap<>();
        public List<Models.StateRow> states;
        public List<Models.IntensityRow> intensities;
        public Map<Integer, Double> dark;
        public String darkUnit;
        public Map<Integer, double[]> gains;
        public double[][] wGenerator;
        public double[][] wAnalyser;
        public Models.UnitValue wavelength;
        public double[][] covariance;
        public int dimension;
        public List<String> diagnostics = new ArrayList<>();
    }

    public static Parsed parse(Models.MaterialType type, byte[] bytes) {
        Parsed p = new Parsed();
        p.type = type;
        List<String> dataLines = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = line.substring(1, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    p.metadata.put(k, v);
                }
                continue;
            }
            dataLines.add(line);
        }
        switch (type) {
            case OPTICAL_STATE:
                parseStates(p, dataLines);
                break;
            case INTENSITY:
                parseIntensity(p, dataLines);
                break;
            case DARK:
                parseDark(p, dataLines);
                break;
            case REFERENCE:
                parseReference(p, dataLines);
                break;
            case INSTRUMENT:
                parseInstrument(p, dataLines);
                break;
            case WAVELENGTH:
                parseWavelength(p, dataLines);
                break;
            case COVARIANCE:
                parseCovariance(p, dataLines);
                break;
            default:
                throw new IllegalArgumentException("unsupported type " + type);
        }
        return p;
    }

    private static List<String[]> rows(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        for (String l : lines) {
            String[] cells = l.split(",", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim();
            }
            out.add(cells);
        }
        return out;
    }

    private static void parseStates(Parsed p, List<String> lines) {
        List<String[]> table = rows(lines);
        String[] header = table.get(0);
        List<String> stokesHeader = new ArrayList<>();
        for (int c = 3; c < header.length; c++) {
            stokesHeader.add(header[c]);
        }
        String orderMeta = p.metadata.getOrDefault("stokes_order", "S0,S1,S2,S3");
        List<String> recordedOrder = Arrays.asList(orderMeta.split(","));
        int sign = parseIntSafe(p.metadata.get("rotation_sign"), 1);
        int[] perm = Conventions.orderPermutation(recordedOrder);
        p.metadata.put("__stokesOrder", orderMeta);
        p.metadata.put("__rotationSign", String.valueOf(sign));

        p.states = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            String[] c = table.get(r);
            Models.StateRow row = new Models.StateRow();
            row.stateId = c[0];
            row.azimuthDeg = Double.parseDouble(c[1]);
            row.ellipticityDeg = Double.parseDouble(c[2]);
            row.angleUnit = p.metadata.getOrDefault("angle_unit", "deg");
            row.coordinateBasis = p.metadata.getOrDefault("coordinate_basis", "lab");
            row.stokesHeader = new ArrayList<>(stokesHeader);
            double[] recorded = new double[4];
            for (int k = 0; k < 4; k++) {
                Models.UnitValue uv =
                        Conventions.parseUnitValue(c[3 + k], "dimensionless");
                row.stokesValues.add(uv);
                row.rawStokes.add(uv.raw);
                recorded[k] = uv.convertible ? uv.value : 0.0;
                if (!uv.convertible) {
                    p.diagnostics.add(
                            "state " + row.stateId + " component " + stokesHeader.get(k)
                                    + " unconvertible: " + uv.diagnostic);
                }
            }
            row.canonicalStokes = Conventions.toCanonical(recorded, perm, sign);
            p.states.add(row);
        }
    }

    private static void parseIntensity(Parsed p, List<String> lines) {
        List<String[]> table = rows(lines);
        p.intensities = new ArrayList<>();
        String unit = p.metadata.getOrDefault("unit", "count");
        for (int r = 1; r < table.size(); r++) {
            String[] c = table.get(r);
            Models.IntensityRow row = new Models.IntensityRow();
            row.idx = Integer.parseInt(c[0]);
            row.generatorStateId = c[1];
            row.analyserStateId = c[2];
            row.value = Conventions.parseUnitValue(c[3], unit);
            if (!row.value.convertible) {
                p.diagnostics.add("intensity row " + row.idx + ": " + row.value.diagnostic);
            }
            p.intensities.add(row);
        }
    }

    private static void parseDark(Parsed p, List<String> lines) {
        List<String[]> table = rows(lines);
        p.dark = new LinkedHashMap<>();
        p.darkUnit = p.metadata.getOrDefault("unit", "count");
        for (int r = 1; r < table.size(); r++) {
            String[] c = table.get(r);
            Models.UnitValue uv = Conventions.parseUnitValue(c[1], p.darkUnit);
            if (uv.convertible) {
                p.dark.put(Integer.parseInt(c[0]), uv.value);
            } else {
                p.diagnostics.add("dark row " + c[0] + " unconvertible");
            }
        }
    }

    private static void parseReference(Parsed p, List<String> lines) {
        List<String[]> table = rows(lines);
        p.gains = new LinkedHashMap<>();
        for (int r = 1; r < table.size(); r++) {
            String[] c = table.get(r);
            double g = Double.parseDouble(c[1]);
            double u = c.length > 2 ? Double.parseDouble(c[2]) : 0.0;
            p.gains.put(Integer.parseInt(c[0]), new double[]{g, u});
        }
    }

    private static void parseInstrument(Parsed p, List<String> lines) {
        List<double[]> gBlock = new ArrayList<>();
        List<double[]> aBlock = new ArrayList<>();
        List<double[]> current = null;
        for (String line : lines) {
            if (line.startsWith("[")) {
                if (line.contains("W_G") || line.toLowerCase().contains("generator")) {
                    current = gBlock;
                } else {
                    current = aBlock;
                }
                continue;
            }
            String[] c = line.split(",", -1);
            double[] v = new double[4];
            for (int i = 0; i < 4; i++) {
                v[i] = Double.parseDouble(c[i].trim());
            }
            current.add(v);
        }
        p.wGenerator = gBlock.toArray(new double[0][]);
        p.wAnalyser = aBlock.toArray(new double[0][]);
    }

    private static void parseWavelength(Parsed p, List<String> lines) {
        List<String[]> table = rows(lines);
        String[] c = table.get(0);
        p.wavelength = Conventions.parseUnitValue(c[1], "nm");
        if (!"nm".equals(p.wavelength.unit)) {
            p.diagnostics.add("wavelength unit not nm: " + p.wavelength.unit);
        }
    }

    private static void parseCovariance(Parsed p, List<String> lines) {
        List<String[]> table = rows(lines);
        int n = table.size();
        p.dimension = n;
        p.covariance = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                String cell = table.get(i)[j];
                try {
                    p.covariance[i][j] = Double.parseDouble(cell);
                } catch (NumberFormatException e) {
                    p.covariance[i][j] = 0.0;
                    p.diagnostics.add("covariance[" + i + "," + j + "] unconvertible: " + cell);
                }
            }
        }
    }

    private static int parseIntSafe(String s, int dflt) {
        if (s == null) {
            return dflt;
        }
        return Integer.parseInt(s.trim());
    }
}
