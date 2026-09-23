package pol.math;

/** Minimal complex number. */
public final class C {
    public final double re, im;

    public C(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public static C of(double re) { return new C(re, 0); }

    public C add(C o) { return new C(re + o.re, im + o.im); }
    public C sub(C o) { return new C(re - o.re, im - o.im); }
    public C mul(C o) { return new C(re * o.re - im * o.im, re * o.im + im * o.re); }
    public C mul(double k) { return new C(re * k, im * k); }
    public C conj() { return new C(re, -im); }
    public double abs2() { return re * re + im * im; }

    public String toString() { return String.format("%.4g%+.4gi", re, im); }
}
