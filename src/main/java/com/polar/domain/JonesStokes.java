package com.polar.domain;

/**
 * Jones 光学状态编码：发生器(PSG)/分析器(PSA)元件链，按约定角度正向传播。
 * 所有角度单位为 rad；Stokes S3 符号与旋转正向取自 {@link Conventions}，二者进入指纹。
 *
 * Stokes 定义（显式坐标基准）：
 *   S0=|Ex|^2+|Ey|^2, S1=|Ex|^2-|Ey|^2,
 *   S2=2 Re(Ex Ey*),  S3=2*vSign*Im(Ex Ey*).
 */
public final class JonesStokes {

    /** 光学元件：类型（LP=线偏振片, WR=延迟片）, 方位角 rad, 延迟 rad(仅延迟片)。 */
    public record Element(String type, double azimuthRad, double retardanceRad) {}

    public record Complex(double re, double im) {
        public Complex mul(Complex o) { return new Complex(re * o.re - im * o.im, re * o.im + im * o.re); }
        public Complex conj() { return new Complex(re, -im); }
        public double abs2() { return re * re + im * im; }
        static Complex polar(double r, double phi) { return new Complex(r * Math.cos(phi), r * Math.sin(phi)); }
    }

    private JonesStokes() {}

    public static double[] jones(Element[] chain, Conventions conv) {
        double[] e = {1.0, 0.0}; // Ex(re), Ex(im), Ey(re), Ey(im)
        for (Element el : chain) {
            double[] m = jonesElement(el, conv);
            apply(m, e);
        }
        return e;
    }

    public static double[] stokes(Element[] chain, Conventions conv) {
        double[] e = jones(chain, conv);
        Complex ex = new Complex(e[0], e[1]);
        Complex ey = new Complex(e[2], e[3]);
        Complex exEy = ex.mul(ey.conj());
        double s0 = ex.abs2() + ey.abs2();
        double s1 = ex.abs2() - ey.abs2();
        double s2 = 2.0 * exEy.re();
        double s3 = 2.0 * conv.vSign() * exEy.im();
        return reorder(new double[]{s0, s1, s2, s3}, conv);
    }

    private static double[] reorder(double[] standard, Conventions conv) {
        int[] perm = conv.permutationFromStandard();
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = standard[perm[i]];
        return out;
    }

    private static void apply(double[] m, double[] e) {
        double ar = m[0] * e[0] - m[1] * e[1] + m[2] * e[2] - m[3] * e[3];
        double ai = m[0] * e[1] + m[1] * e[0] + m[2] * e[3] + m[3] * e[2];
        double br = m[4] * e[0] - m[5] * e[1] + m[6] * e[2] - m[7] * e[3];
        double bi = m[4] * e[1] + m[5] * e[0] + m[6] * e[3] + m[7] * e[2];
        e[0] = ar; e[1] = ai; e[2] = br; e[3] = bi;
    }

    private static double[] jonesElement(Element el, Conventions conv) {
        double[] m0;
        if ("LP".equals(el.type())) {
            m0 = new double[]{0.5, 0, 0.5, 0, 0, 0.5, 0, 0.5};
        } else if ("WR".equals(el.type())) {
            double d = el.retardanceRad();
            double a = Math.cos(d / 2);
            double b = Math.sin(d / 2);
            m0 = new double[]{a, -b, 0, 0, 0, 0, a, b}; // diag(e^{-iδ/2}, e^{iδ/2})
        } else {
            throw new IllegalArgumentException("未知元件类型: " + el.type());
        }
        return rotate(m0, conv.rotationSign() * el.azimuthRad());
    }

    /** M_θ = R M_0 R^T（Jones 旋转相似变换，2x2 复数）。 */
    static double[] rotate(double[] m, double theta) {
        double c = Math.cos(theta), s = Math.sin(theta);
        double[] r = {c, 0, -s, 0, s, 0, c, 0};
        double[] rt = {c, 0, s, 0, -s, 0, c, 0};
        double[] t = mmul(r, m);
        return mmul(t, rt);
    }

    static double[] mmul(double[] a, double[] b) {
        double[] o = new double[8];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                double re = 0, im = 0;
                for (int k = 0; k < 2; k++) {
                    re += a[4 * i + 2 * k] * b[4 * k + 2 * j]
                            - a[4 * i + 2 * k + 1] * b[4 * k + 2 * j + 1];
                    im += a[4 * i + 2 * k] * b[4 * k + 2 * j + 1]
                            + a[4 * i + 2 * k + 1] * b[4 * k + 2 * j];
                }
                o[4 * i + 2 * j] = re;
                o[4 * i + 2 * j + 1] = im;
            }
        }
        return o;
    }

    /** 分析器对入射 Stokes 的理想透光强度：S·S_accept / 2（接收态 Stokes 归一化为 S0=1）。 */
    public static double analyze(double[] incidentStokes, double[] acceptStokes) {
        double dot = 0;
        for (int i = 0; i < 4; i++) dot += incidentStokes[i] * acceptStokes[i];
        return dot / 2.0;
    }
}
