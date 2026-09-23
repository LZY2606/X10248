package org.polarlab.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.polarlab.db.Item;
import org.polarlab.domain.Convention;
import org.polarlab.domain.Mat;
import org.polarlab.domain.PolarOptics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把“原始来源/解释分离”的 item 集组装为偏振测量数据集。
 * 原始表示（rawText/rawUnit/frame）保留在 diagnostics 中；派生层才规范化。
 * 无法转换的值留在原位并附诊断，不为画图丢弃。
 */
public class Dataset {
    public Convention convention = Convention.standard();
    public final List<Row> rows = new ArrayList<>();
    public double[] darkCounts;
    public Mat instrument;
    public Mat reference;
    public double[] variances;
    public String wavelengthId;
    public String covarianceId;
    public String darkId;
    public String instrumentId;
    public String referenceId;
    public final List<String> diagnostics = new ArrayList<>();
    public final Map<String, Integer> itemVersions = new LinkedHashMap<>();

    public record Row(int index, String generatorId, String analyzerId,
                      String gKind, String aKind, double gTheta, double aTheta,
                      double rawIntensity, double intensity, String rawText, String rawUnit,
                      String frame, boolean convertible, String diagnostic) {}

    public static Dataset assemble(List<Item> items) {
        Dataset ds = new Dataset();
        JsonObject states = new JsonObject();
        Map<String, JsonObject> byKind = new LinkedHashMap<>();
        for (Item it : items) {
            ds.itemVersions.put(it.itemId(), it.version());
            switch (it.kind()) {
                case "optic-state" -> states.add(it.itemId(), it.json());
                case "wavelength" -> ds.wavelengthId = it.itemId();
                case "covariance" -> ds.covarianceId = it.itemId();
                case "dark" -> { ds.darkId = it.itemId(); byKind.put("dark", it.json()); }
                case "instrument" -> { ds.instrumentId = it.itemId(); byKind.put("instrument", it.json()); }
                case "reference" -> { ds.referenceId = it.itemId(); byKind.put("reference", it.json()); }
                default -> { /* intensity below */ }
            }
        }
        String order = null, sign = null;
        for (Item it : items)
            if (it.kind().equals("optic-state")) {
                JsonObject j = it.json();
                if (j.has("stokesOrder") && order == null) order = j.get("stokesOrder").getAsString();
                if (j.has("rotationSign") && sign == null) sign = j.get("rotationSign").getAsString();
            }
        if (order != null || sign != null)
            ds.convention = new Convention(order == null ? "ISQUV" : order,
                    sign == null ? 1 : Integer.parseInt(sign));

        List<Item> intensities = new ArrayList<>();
        for (Item it : items) if (it.kind().equals("intensity")) intensities.add(it);
        intensities.sort((a, b) -> safeInt(a.json(), "index") - safeInt(b.json(), "index"));

        ds.darkCounts = readDark(byKind.get("dark"), ds);
        ds.instrument = readMatrix(byKind.get("instrument"), "instrument", ds);
        ds.reference = readMatrix(byKind.get("reference"), "reference", ds);
        ds.variances = readCovariance(items, ds, intensities.size());

        for (int k = 0; k < intensities.size(); k++) {
            Item it = intensities.get(k);
            JsonObject j = it.json();
            int index = safeInt(j, "index");
            String gId = j.get("generatorStateId").getAsString();
            String aId = j.get("analyzerStateId").getAsString();
            JsonObject g = states.has(gId) ? states.getAsJsonObject(gId) : null;
            JsonObject a = states.has(aId) ? states.getAsJsonObject(aId) : null;
            if (g == null || a == null) {
                ds.diagnostics.add("intensity " + it.itemId() + " references missing state " + gId + "/" + aId);
                continue;
            }
            JsonObject q = j.getAsJsonObject("quantity");
            double raw = q.get("value").getAsDouble();
            boolean convertible = q.get("convertible").getAsBoolean();
            String diag = q.has("diagnostic") ? q.get("diagnostic").getAsString() : null;
            double dark = ds.darkCounts != null && index < ds.darkCounts.length ? ds.darkCounts[index] : 0;
            double value = convertible ? raw - dark : raw;
            if (!convertible) ds.diagnostics.add("intensity " + it.itemId()
                    + " kept unconverted: " + (diag == null ? "" : diag));
            ds.rows.add(new Row(index, gId, aId,
                    g.get("kind").getAsString(), a.get("kind").getAsString(),
                    angle(g, ds), angle(a, ds),
                    raw, value,
                    q.get("rawText").getAsString(),
                    q.get("rawUnit").getAsString(),
                    q.get("coordinateFrame").getAsString(),
                    convertible, diag));
        }
        return ds;
    }

    private static int safeInt(JsonObject j, String k) {
        return j.has(k) ? j.get(k).getAsInt() : Integer.MAX_VALUE;
    }

    private static double angle(JsonObject state, Dataset ds) {
        JsonObject q = state.getAsJsonObject("angle");
        if (!q.get("convertible").getAsBoolean()) {
            ds.diagnostics.add("optic state angle kept unconverted (" + state.get("stateId") + "): "
                    + (q.has("diagnostic") ? q.get("diagnostic").getAsString() : ""));
        }
        return q.get("value").getAsDouble();
    }

    private static double[] readDark(JsonObject j, Dataset ds) {
        if (j == null) return null;
        JsonArray arr = j.getAsJsonArray("counts");
        double[] out = new double[arr.size()];
        boolean allConv = true;
        for (int i = 0; i < arr.size(); i++) {
            JsonObject q = arr.get(i).getAsJsonObject();
            out[i] = q.get("value").getAsDouble();
            if (!q.get("convertible").getAsBoolean()) allConv = false;
        }
        if (!allConv) ds.diagnostics.add("dark frame contains unconverted counts; subtraction skipped per-cell semantics retained");
        return out;
    }

    private static Mat readMatrix(JsonObject j, String label, Dataset ds) {
        if (j == null) return null;
        if (j.has("convertible") && !j.get("convertible").getAsBoolean()) {
            ds.diagnostics.add(label + " matrix unconvertible, kept out of normalization: "
                    + (j.has("diagnostic") ? j.get("diagnostic").getAsString() : ""));
            return null;
        }
        JsonArray rows = j.getAsJsonArray("elements");
        Mat m = new Mat(4, 4);
        for (int i = 0; i < 4; i++)
            for (int kk = 0; kk < 4; kk++)
                m.set(i, kk, rows.get(i * 4 + kk).getAsDouble());
        return m;
    }

    private static double[] readCovariance(List<Item> items, Dataset ds, int n) {
        Item cov = null;
        for (Item it : items) if (it.kind().equals("covariance")) cov = it;
        if (cov == null) return null;
        JsonObject j = cov.json();
        JsonArray diag = j.getAsJsonArray("diagonal");
        double[] out = new double[diag.size()];
        boolean correlated = j.has("correlated") && j.get("correlated").getAsBoolean();
        for (int i = 0; i < diag.size(); i++) {
            JsonElement e = diag.get(i);
            if (e.isJsonObject()) {
                JsonObject q = e.getAsJsonObject();
                out[i] = q.get("value").getAsDouble();
                if (!q.get("convertible").getAsBoolean())
                    ds.diagnostics.add("covariance[" + i + "] unconvertible, treated as unit weight");
            } else out[i] = e.getAsDouble();
        }
        if (correlated) ds.diagnostics.add("covariance marked correlated; error propagation uses declared variances");
        return out;
    }

    public List<PolarOptics.StatePair> statePairs() {
        List<PolarOptics.StatePair> out = new ArrayList<>();
        for (Row r : rows)
            out.add(new PolarOptics.StatePair(
                    new PolarOptics.OpticState(r.gKind, r.gTheta, r.generatorId),
                    new PolarOptics.OpticState(r.aKind, r.aTheta, r.analyzerId)));
        return out;
    }

    public double[] intensityVector() {
        double[] y = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) y[i] = rows.get(i).intensity;
        return y;
    }
}
