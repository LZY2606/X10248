package com.polar.domain;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * 领域量的单位与坐标基准。导入保留原始表示；派生层才规范化。
 * 无法转换的值保留在原位置并附诊断，绝不为绘图丢弃。
 */
public final class Units {

    public record Parsed(String raw, String unit, OptionalDouble valueSi, String diagnostic) {
        public boolean convertible() { return valueSi.isPresent(); }
    }

    private Units() {}

    /** 波长 → SI(米)。支持 nm / um / μm / Å / m。 */
    public static Parsed parseWavelength(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(raw, "", OptionalDouble.empty(), "EMPTY_WAVELENGTH");
        }
        String t = raw.trim().toLowerCase(Locale.ROOT).replace("μ", "u").replace("µ", "u");
        String unit = "";
        String num = t;
        double factor;
        if (t.endsWith("nm")) { unit = "nm"; num = t.substring(0, t.length() - 2); factor = 1e-9; }
        else if (t.endsWith("um")) { unit = "um"; num = t.substring(0, t.length() - 2); factor = 1e-6; }
        else if (t.endsWith("angstrom") || t.endsWith("å")) { unit = "Angstrom"; num = t.replace("angstrom","").replace("å",""); factor = 1e-10; }
        else if (t.endsWith("a")) { unit = "Angstrom"; num = t.substring(0, t.length() - 1); factor = 1e-10; }
        else if (t.endsWith("m")) { unit = "m"; num = t.substring(0, t.length() - 1); factor = 1; }
        else {
            return new Parsed(raw, "", OptionalDouble.empty(), "UNKNOWN_WAVELENGTH_UNIT:" + raw);
        }
        try {
            double v = Double.parseDouble(num.trim());
            return new Parsed(raw, unit, OptionalDouble.of(v * factor), null);
        } catch (NumberFormatException e) {
            return new Parsed(raw, unit, OptionalDouble.empty(), "UNPARSEABLE_WAVELENGTH_VALUE:" + raw);
        }
    }

    /** 强度/角度等单位白名单（仅记录与核对，不做隐式换算）。 */
    public static boolean knownIntensityUnit(String u) {
        return u == null || u.isBlank() || u.equals("counts") || u.equals("W/m^2") || u.equals("a.u.");
    }

    public static boolean knownAngleUnit(String u) {
        return u == null || u.isBlank() || u.equals("rad") || u.equals("deg");
    }

    public static double angleToRad(double v, String unit) {
        if ("deg".equals(unit)) return Math.toRadians(v);
        return v;
    }
}
