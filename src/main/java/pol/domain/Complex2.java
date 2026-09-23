package pol.domain;

/** Minimal immutable complex number used by the Cloude map construction. */
public final class Complex2 {
    public static final Complex2 ZERO = new Complex2(0, 0);
    public final double re;
    public final double im;

    public Complex2(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public Complex2 plus(Complex2 o) {
        return new Complex2(re + o.re, im + o.im);
    }

    public Complex2 mult(Complex2 o) {
        return new Complex2(re * o.re - im * o.im, re * o.im + im * o.re);
    }

    public Complex2 scale(double s) {
        return new Complex2(re * s, im * s);
    }

    public Complex2 conj() {
        return new Complex2(re, -im);
    }
}
