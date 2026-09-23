package poloptics.casepack;

import poloptics.domain.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline domain case pack:
 * "偏振矩阵室：Mueller 测量、仪器校正与物理可实现性审查".
 *
 * Fixed narrative with seven situations:
 *  0 BASELINE                separated sources, physical polarizer-ish sample
 *  1 DARK_REVISION           dark field corrected -> scoped invalidation
 *  2 SINGULAR_DESIGN         duplicate generator/analyzer rows, rank deficient
 *  3 STOKES_ORDER            reordered Stokes basis -> fingerprint changes
 *  4 ROTATION_SIGN           CW positive convention -> fingerprint changes
 *  5 NONPHYSICAL_NOISE       noisy unphysical candidate flagged, not projected
 *  6 REFERENCE_VERSION       reference sample revision + correlated errors
 *
 * Accepted and rejected results are both retained; replay events are machine
 * readable and carry fingerprints/invalidation scope.
 */
public final class CasePack {
    private CasePack() {}

    public static final String BASELINE_BRANCH = "branch-base";
    public static final String NEARSINGULAR_BRANCH = "branch-nearsingular";

    public static final class ItemSpec {
        public final String kind, role, mediaType, unit, text, version;
        public ItemSpec(String kind, String role, String mediaType, String unit, String text, String version) {
            this.kind=kind;this.role=role;this.mediaType=mediaType;this.unit=unit;this.text=text;this.version=version;
        }
    }

    // ---------- synthetic-but-physical baseline ----------
    // 6 PSG x 4 PSA angular configurations; intensities generated from a known
    // Mueller matrix (partial polarizer + weak retardance) + tiny noise.

    static double[][] knownMueller() {
        // partial linear polarizer at 30 deg combined with small retardance
        double theta = Math.toRadians(30);
        double[][] p = poloptics.engine.Polarization.linearPolarizer(theta, true);
        double[][] r = poloptics.engine.Polarization.retarder(theta, 0.35, true);
        double[][] m = poloptics.engine.Mat.mul(p, r);
        return m;
    }

    static String intensityText(double dark, double gain, boolean singular,
                                boolean nonphysical, double[][] mOverride, Long seed) {
        java.util.Random rnd = new java.util.Random(seed == null ? 42 : seed);
        double[][] m = mOverride != null ? mOverride : knownMueller();
        int[] ga = singular ? new int[]{0,0,0,45,45,45} : new int[]{0,30,60,90,120,150};
        int[] aa = singular ? new int[]{0,0,0,45,45,45} : new int[]{0,45,90,135};
        StringBuilder sb = new StringBuilder("# thetaG_deg thetaA_deg intensity_counts (unit=count)\n");
        for (int gdeg : ga) for (int adeg : aa) {
            double[] g = poloptics.engine.Polarization.generatorStokes(Math.toRadians(gdeg));
            double[] a = poloptics.engine.Polarization.analyzerStokes(Math.toRadians(adeg));
            double ideal = 0;
            for (int i=0;i<4;i++) for (int j=0;j<4;j++) ideal += a[i]*m[i][j]*g[j];
            double noise = rnd.nextGaussian() * (nonphysical ? 0.35 : 0.004);
            double counts = gain * ideal + dark + noise;
            if (nonphysical) counts += (gdeg + adeg) % 90 == 0 ? 0.5 : -0.4;
            sb.append(gdeg).append(' ').append(adeg).append(' ')
              .append(String.format(java.util.Locale.ROOT, "%.5f%n", counts));
        }
        return sb.toString();
    }

    static String stateText(boolean singular, boolean cw, String order) {
        int[] ga = singular ? new int[]{0,0,0,45,45,45} : new int[]{0,30,60,90,120,150};
        int[] aa = singular ? new int[]{0,0,0,45,45,45} : new int[]{0,45,90,135};
        StringBuilder sb = new StringBuilder("# PSG/PSA optical state codes; angles in degree; ");
        sb.append(cw ? "rotation=CW_POSITIVE" : "rotation=CCW_POSITIVE");
        sb.append("\n# order: ").append(order).append('\n');
        for (int g : ga) for (int a : aa) sb.append(g).append(' ').append(a).append('\n');
        return sb.toString();
    }

    static String constText(double value, int n, String unit) {
        StringBuilder sb = new StringBuilder("# unit=").append(unit).append('\n');
        for (int i=0;i<n;i++) sb.append(String.format(java.util.Locale.ROOT, "%.5f%n", value));
        return sb.toString();
    }

    static String varianceText(int n, double v) {
        StringBuilder sb = new StringBuilder("# unit=count^2\n");
        for (int i=0;i<n;i++) sb.append(String.format(java.util.Locale.ROOT, "%.5f%n", v));
        return sb.toString();
    }

    static String covarianceText(int n, double diag, double off) {
        StringBuilder sb = new StringBuilder("# correlated measurement covariance; unit=count^2\n");
        for (int i=0;i<n;i++) {
            for (int j=0;j<n;j++) {
                double v = i==j ? diag : off * Math.exp(-Math.abs(i-j)/3.0);
                sb.append(String.format(java.util.Locale.ROOT, "%.6f%s", v, j<n-1?" ":"\n"));
            }
        }
        return sb.toString();
    }

    static String instrumentText() {
        return """
               # PSG/PSA instrument calibration matrix W (unitless; maps states->Stokes rows)
               # version=cal-2026-09-v1
               1.000 0.000 0.000 0.000
               1.000 0.000 0.000 0.000
               0.000 1.000 0.000 0.000
               0.000 0.000 1.000 0.000
               """;
    }

    public static final String REJECTION_RULES = """
            # 判定规则（机器可读）
            # RULE-NEAR-SINGULAR: rank<16 -> 不强行求逆，保留特解+零空间全部合法候选
            # RULE-NONPHYSICAL: 标记违约残差，禁止投影后冒充原解
            # RULE-FINGERPRINT: 旋转约定与 Stokes 顺序进入指纹，变化即新建分支/事件
            # RULE-INVALIDATION: 参数/参考版本变化仅使依赖子树失效，禁止全局静默替换
            """;
}
