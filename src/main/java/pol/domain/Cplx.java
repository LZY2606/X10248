package pol.domain;

/** Minimal immutable complex number used for the Pauli/Kronecker algebra. */
public record Cplx(double re, double im) {
    public static final Cplx ZERO = new Cplx(0, 0);

    public static Cplx add(Cplx a, Cplx b) {
        return new Cplx(a.re + b.re, a.im + b.im);
    }

    public static Cplx mul(Cplx a, Cplx b) {
        return new Cplx(a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re);
    }

    public Cplx conj() {
        return new Cplx(re, -im);
    }
}
