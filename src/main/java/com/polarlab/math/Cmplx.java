package com.polarlab.math;

import java.util.Locale;

/** Immutable complex number used for coherency/covariance polarization algebra. */
public final class Cmplx {
    public static final Cmplx ZERO = new Cmplx(0, 0);
    public static final Cmplx ONE = new Cmplx(1, 0);
    public static final Cmplx I = new Cmplx(0, 1);

    public final double re, im;

    public Cmplx(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public static Cmplx of(double re) {
        return new Cmplx(re, 0);
    }

    public Cmplx add(Cmplx o) {
        return new Cmplx(re + o.re, im + o.im);
    }

    public Cmplx sub(Cmplx o) {
        return new Cmplx(re - o.re, im - o.im);
    }

    public Cmplx mul(double s) {
        return new Cmplx(re * s, im * s);
    }

    public Cmplx mul(Cmplx o) {
        return new Cmplx(re * o.re - im * o.im, re * o.im + im * o.re);
    }

    public Cmplx conj() {
        return new Cmplx(re, -im);
    }

    public double abs2() {
        return re * re + im * im;
    }

    public double abs() {
        return Math.hypot(re, im);
    }

    public Cmplx neg() {
        return new Cmplx(-re, -im);
    }

    public boolean equals(Cmplx o, double tol) {
        return sub(o).abs2() <= tol * tol;
    }

    @Override
    public String toString() {
        if (Math.abs(im) < 1e-15) return String.format(Locale.ROOT, "%.6g", re);
        if (Math.abs(re) < 1e-15) return String.format(Locale.ROOT, "%.6gi", im);
        return String.format(Locale.ROOT, "%.6g%+.6gi", re, im);
    }
}
