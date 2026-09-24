package org.polarworkbench.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CasePack {
    private CasePack() {
    }

    public static List<String> stateNames(String scenario) {
        return switch (scenario) {
            case "singular" -> List.of("H", "V", "P", "M");
            case "near-singular" -> List.of("H", "V", "P", "P_NEAR");
            default -> List.of("H", "V", "P", "R");
        };
    }

    public static Map<String, Object> opticalSource(String scenario, String version, String stokesOrder,
                                                    String rotationSign, boolean changed) {
        List<Map<String, Object>> states = new ArrayList<>();
        for (String name : stateNames(scenario)) {
            double angle = switch (name) {
                case "V" -> 90.0;
                case "M" -> -45.0;
                case "P_NEAR" -> 45.003;
                default -> 45.0;
            };
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("stateId", name.toLowerCase());
            state.put("name", name);
            state.put("angleDegrees", angle);
            state.put("angleUnit", "degree");
            state.put("coordinateBasis", "laboratory_lab_frame");
            states.add(state);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("states", states);
        raw.put("stokesOrder", stokesOrder);
        raw.put("rotationSignConvention", rotationSign);
        raw.put("stokesComponentUnit", "dimensionless_normalized_intensity");
        raw.put("importNote", changed ? "rotating-element interpretation revised" : "imported laboratory notes");
        return source("optical", version, raw, "Stokes coordinates: " + stokesOrder
                + "; rotation sign: " + rotationSign);
    }

    public static Map<String, Object> intensitySource(String scenario, String version, double[] mueller,
                                                      double dark, String stokesOrder, String rotationSign) {
        List<String> names = stateNames(scenario);
        List<Double> values = new ArrayList<>();
        for (String generatorName : names) {
            double[] generator = PolarBridge.stokes(generatorName, 0, rotationSign, stokesOrder);
            for (String analyzerName : names) {
                double[] analyzer = PolarBridge.stokes(analyzerName, 0, rotationSign, stokesOrder);
                double ideal = 0.0;
                for (int row = 0; row < 4; row++) {
                    for (int column = 0; column < 4; column++) {
                        ideal += mueller[4 * row + column] * analyzer[row] * generator[column];
                    }
                }
                values.add(Math.round(ideal * 100.0) + dark);
            }
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("valuesCount", values);
        raw.put("unit", "count");
        raw.put("layout", "row_major_generator_then_analyzer");
        raw.put("generatorStateIds", names.stream().map(String::toLowerCase).toList());
        raw.put("analyzerStateIds", names.stream().map(String::toLowerCase).toList());
        raw.put("incidentScaleCounts", 100);
        raw.put("integrationTimeText", "1.0 s");
        raw.put("cameraThermalNote", "warm: retained as unconvertible ancillary text");
        return source("intensity", version, raw, "count; camera detector count");
    }

    public static Map<String, Object> darkSource(String version, double value) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("valuesCount", value);
        raw.put("unit", "count");
        raw.put("maskingPolicy", "diagnostic metadata retained; no irreversible clipping");
        return source("dark", version, raw, "count; detector dark offset");
    }

    public static Map<String, Object> referenceSource(String version, double transmittance) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("sampleId", "air-reference-" + version);
        raw.put("transmittance", transmittance);
        raw.put("unit", "dimensionless");
        return source("reference", version, raw, "dimensionless reference transmittance");
    }

    public static Map<String, Object> instrumentSource(String version, double diagonalFactor) {
        double[][] matrix = new double[4][4];
        for (int index = 0; index < 4; index++) {
            matrix[index][index] = diagonalFactor;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("measuredInstrumentMatrix", matrix);
        raw.put("matrixUnit", "dimensionless");
        raw.put("correctionBasis", "M_corrected = A_measured^{-1} M_raw / t_reference");
        raw.put("wavelengthBasis", "measurement wavelength");
        return source("instrument", version, raw, "dimensionless 4x4 instrument matrix");
    }

    public static Map<String, Object> wavelengthSource(String version, double nm) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("wavelengthText", nm + " nm");
        raw.put("wavelengthNm", nm);
        raw.put("unit", "nm");
        return source("wavelength", version, raw, "nm vacuum wavelength annotation");
    }

    public static Map<String, Object> covarianceSource(String version, boolean correlated) {
        double[][] matrix = new double[16][16];
        for (int i = 0; i < 16; i++) {
            matrix[i][i] = correlated ? 1.25 : 1.0;
            if (correlated) {
                if (i + 1 < 16 && i % 4 != 3) {
                    matrix[i][i + 1] = 0.25;
                    matrix[i + 1][i] = 0.25;
                }
            }
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("measurementCovariance", matrix);
        raw.put("unit", "count^2");
        raw.put("correlationModel", correlated ? "nearest-neighbor analyzer correlations" : "independent detector counts");
        return source("covariance", version, raw, "count^2 intensity covariance");
    }

    private static Map<String, Object> source(String type, String version, Map<String, Object> raw,
                                              String unitOrBasis) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceKey", type);
        map.put("version", version);
        map.put("sourceType", type);
        map.put("raw", raw);
        map.put("unitOrBasis", unitOrBasis);
        return map;
    }

    public static final double[] BASELINE_M = {
            .5, 0, 0, 0,
            0, .35, 0, 0,
            0, 0, .30, 0,
            0, 0, 0, .25
    };

    public static final double[] UNPHYSICAL_M = {
            1, 0, 0, 0,
            0, .9, 0, 0,
            0, 0, .8, 0,
            0, 0, 0, -.75
    };

    public static Map<String, Map<String, Object>> baselineSources() {
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        add(sources, opticalSource("baseline", "v1", "I,Q,U,V", "PLUS_THETA", false));
        add(sources, intensitySource("baseline", "v1", BASELINE_M, 10, "I,Q,U,V", "PLUS_THETA"));
        add(sources, darkSource("v1", 10));
        add(sources, referenceSource("v1", 1.0));
        add(sources, instrumentSource("v1", 1.0));
        add(sources, wavelengthSource("v1", 632.8));
        add(sources, covarianceSource("v1", false));
        return sources;
    }

    private static void add(Map<String, Map<String, Object>> sources, Map<String, Object> source) {
        sources.put((String) source.get("sourceKey"), source);
    }
}
