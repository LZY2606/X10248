package pol.math;

public final class Cplx {
    public final double re, im;
    public Cplx(double re, double im) { this.re = re; this.im = im; }
    public static Cplx r(double v) { return new Cplx(v, 0); }
    public Cplx add(Cplx o) { return new Cplx(re + o.re, im + o.im); }
    public Cplx sub(Cplx o) { return new Cplx(re - o.re, im - o.im); }
    public Cplx mul(Cplx o) { return new Cplx(re * o.re - im * o.im, re * o.im + im * o.re); }
    public Cplx neg() { return new Cplx(-re, -im); }
    public Cplx conj() { return new Cplx(re, -im); }
}
