package org.polarlab.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 带单位与坐标基准的测量/参数值。导入时保留原始表示（rawText/rawUnit/rawFrame），
 * 派生层才做规范化（canonicalUnit）。无法转换的值留在原位置并附诊断。
 */
public final class Quantity {
    public final double value;
    public final String rawText;
    public final String rawUnit;
    public final String unit;
    public final String coordinateFrame;
    public final boolean convertible;
    public final String diagnostic;

    private Quantity(double value, String rawText, String rawUnit, String unit,
                     String coordinateFrame, boolean convertible, String diagnostic) {
        this.value = value;
        this.rawText = rawText;
        this.rawUnit = rawUnit;
        this.unit = unit;
        this.coordinateFrame = coordinateFrame;
        this.convertible = convertible;
        this.diagnostic = diagnostic;
    }

    /** 已经是规范单位或可无损转换的量。 */
    public static Quantity of(double value, String unit, String frame) {
        return new Quantity(value, format(value), unit, unit, frame, true, null);
    }

    /** 保留导入时的原始文本；派生层尝试规范化。 */
    public static Quantity imported(String rawText, String rawUnit, String frame) {
        Parsed p = parse(rawText);
        String diagnostic = null;
        boolean convertible = true;
        double value = p.value;
        String canonical = rawUnit;
        if (!p.ok) {
            convertible = false;
            diagnostic = "unparseable numeric representation: " + rawText;
        } else {
            String conv = canonicalUnit(rawUnit);
            if (conv == null) {
                convertible = false;
                diagnostic = "unknown unit, value kept in place without normalization: " + rawUnit;
            } else {
                canonical = conv;
                double factor = factorToCanonical(rawUnit);
                if (Double.isNaN(factor)) {
                    convertible = false;
                    diagnostic = "no conversion path to canonical unit for: " + rawUnit;
                } else {
                    value = p.value * factor;
                }
            }
        }
        return new Quantity(value, rawText, rawUnit, canonical, frame, convertible, diagnostic);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("rawText", rawText);
        m.put("rawUnit", rawUnit == null ? "" : rawUnit);
        m.put("canonicalUnit", unit == null ? "" : unit);
        m.put("coordinateFrame", coordinateFrame == null ? "" : coordinateFrame);
        m.put("convertible", convertible);
        if (diagnostic != null) m.put("diagnostic", diagnostic);
        return m;
    }

    private static final class Parsed { final boolean ok; final double value;
        Parsed(boolean ok, double v) { this.ok = ok; this.value = v; } }

    private static Parsed parse(String s) {
        if (s == null) return new Parsed(false, Double.NaN);
        String t = s.trim().replace(",", ".");
        if (t.isEmpty()) return new Parsed(false, Double.NaN);
        try { return new Parsed(true, Double.parseDouble(t)); }
        catch (NumberFormatException e) { return new Parsed(false, Double.NaN); }
    }

    /** 领域白名单：强度与矩阵元无量纲/计数，角度度，波长 nm，时间 s，协方差为对应单位平方。 */
    private static String canonicalUnit(String u) {
        if (u == null) return "";
        return switch (u.trim()) {
            case "", "counts", "DN", "a.u.", "1", "dimensionless", "mW", "W" ->
                switch (u.trim()) { case "mW" -> "W"; default -> u.trim(); };
            case "deg", "rad", "°" -> "rad";
            case "nm", "um", "µm", "m" -> "nm";
            case "s", "ms" -> "s";
            case "counts^2", "DN^2", "a.u.^2", "1^2", "W^2" ->
                u.trim().equals("W^2") ? "W^2" : u.trim();
            default -> null;
        };
    }

    private static double factorToCanonical(String u) {
        if (u == null) return 1.0;
        return switch (u.trim()) {
            case "mW" -> 1e-3;
            case "deg", "°" -> Math.PI / 180.0;
            case "rad", "" , "counts", "DN", "a.u.", "1", "dimensionless",
                 "W", "nm", "s", "counts^2", "DN^2", "a.u.^2", "1^2", "W^2" -> 1.0;
            case "um", "µm" -> 1000.0;
            case "m" -> 1e9;
            case "ms" -> 1e-3;
            default -> Double.NaN;
        };
    }

    private static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
