package pol.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves generator/analyzer state codes to normalized Stokes components under
 * a declared convention. Both the Stokes component order and the rotation sign
 * feed the convention fingerprint, so a swapped order or flipped rotation
 * produces a different fingerprint and is never silently treated as equal.
 */
public final class PolarStates {

    private final Convention convention;
    private final double[][] wGenerator;
    private final double[][] wAnalyzer;

    public PolarStates(Convention convention, double[][] wGenerator, double[][] wAnalyzer) {
        this.convention = convention;
        this.wGenerator = wGenerator;
        this.wAnalyzer = wAnalyzer;
    }

    /** Ideal normalized Stokes vector in the canonical [S0,S1,S2,S3] frame. */
    public static double[] ideal(String code, String rotationSign) {
        double sign = rotationSign.contains("CW") ? -1.0 : 1.0;
        switch (code.trim().toUpperCase()) {
            case "H":
            case "0":
                return new double[] {1, 1, 0, 0};
            case "V":
            case "90":
                return new double[] {1, -1, 0, 0};
            case "P":
            case "+45":
            case "45":
                return new double[] {1, 0, 1, 0};
            case "M":
            case "-45":
            case "135":
                return new double[] {1, 0, -1, 0};
            case "R":
            case "RCP":
                return new double[] {1, 0, 0, 1};
            case "L":
            case "LCP":
                return new double[] {1, 0, 0, -1};
            default:
                break;
        }
        if (code.toUpperCase().startsWith("L")) {
            double theta = sign * parseAngle(code.substring(1));
            double r = Math.toRadians(theta);
            double c2 = Math.cos(2 * r);
            double s2 = Math.sin(2 * r);
            return new double[] {1, c2, s2, 0};
        }
        throw new IllegalArgumentException("unknown optical state code: " + code);
    }

    private static double parseAngle(String s) {
        try {
            return Double.parseDouble(s.replace("deg", "").trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("bad linear state angle: " + s, e);
        }
    }

    /** Reorder a canonical vector according to the declared Stokes order. */
    public double[] reorder(double[] canonical) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = canonical[indexOf(convention.stokesOrder.get(i))];
        }
        return out;
    }

    private static int indexOf(String component) {
        return switch (component) {
            case "S0" -> 0;
            case "S1" -> 1;
            case "S2" -> 2;
            case "S3" -> 3;
            default -> throw new IllegalArgumentException("unknown Stokes component " + component);
        };
    }

    /** Generator Stokes vector after the generator instrument matrix and reordering. */
    public double[] generator(String code) {
        double[] v = ideal(code, convention.rotationSign);
        if (wGenerator != null) {
            v = apply(wGenerator, v);
        }
        return reorder(v);
    }

    /** Analyzer measurement row: first component of W·[1,S] style, with reorder. */
    public double[] analyzer(String code) {
        double[] v = ideal(code, convention.rotationSign);
        if (wAnalyzer != null) {
            v = apply(wAnalyzer, v);
        }
        return reorder(v);
    }

    private static double[] apply(double[][] w, double[] v) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                out[i] += w[i][j] * v[j];
            }
        }
        return out;
    }

    public static Map<String, double[]> catalog(String rotationSign) {
        Map<String, double[]> map = new LinkedHashMap<>();
        for (String code : new String[] {"H", "V", "P", "M", "R", "L"}) {
            map.put(code, ideal(code, rotationSign));
        }
        return map;
    }
}
