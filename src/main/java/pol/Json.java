package pol;

import com.fasterxml.jackson.databind.*;
import java.util.*;

public final class Json {
    private Json() {}
    public static final ObjectMapper M = new ObjectMapper();

    public static String write(Object o) {
        try { return M.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String s) {
        try { return M.readValue(s, LinkedHashMap.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> readList(String s) {
        try { return M.readValue(s, ArrayList.class); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static double[][] matrix(Object o) {
        List<?> rows = (List<?>) o;
        double[][] m = new double[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            List<?> r = (List<?>) rows.get(i);
            m[i] = new double[r.size()];
            for (int j = 0; j < r.size(); j++) m[i][j] = ((Number) r.get(j)).doubleValue();
        }
        return m;
    }

    public static List<List<Double>> toLists(double[][] m) {
        List<List<Double>> out = new ArrayList<>();
        for (double[] row : m) {
            List<Double> r = new ArrayList<>();
            for (double v : row) r.add(v);
            out.add(r);
        }
        return out;
    }

    public static List<Double> toList(double[] v) {
        List<Double> out = new ArrayList<>();
        for (double x : v) out.add(x);
        return out;
    }
}
