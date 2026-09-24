package io.polwork.linalg;

import java.util.Objects;

/** Immutable complex number. */
public final class Complex {
    public static final Complex ZERO = new Complex(0.0, 0.0);
    public static final Complex ONE = new Complex(1.0, 0.0);
    public static final Complex I = new Complex(0.0, 1.0);

    public final double re;
    public final double im;

    public Complex(double re, double im) {
        this.re = re;
        this.im = im;
    }

    public static Complex of(double re, double im) {
        return new Complex(re, im);
    }

    public Complex add(Complex o) {
        return new Complex(re + o.re, im + o.im);
    }

    public Complex sub(Complex o) {
        return new Complex(re - o.re, im - o.im);
    }

    public Complex mul(Complex o) {
        return new Complex(re * o.re - im * o.im, re * o.im + im * o.re);
    }

    public Complex scale(double s) {
        return new Complex(re * s, im * s);
    }

    public Complex conj() {
        return new Complex(re, -im);
    }

    public double abs2() {
        return re * re + im * im;
    }

    public double abs() {
        return Math.hypot(re, im);
    }

    public Complex neg() {
        return new Complex(-re, -im);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Complex c)) {
            return false;
        }
        return Double.compare(c.re, re) == 0 && Double.compare(c.im, im) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(re, im);
    }

    @Override
    public String toString() {
        if (im == 0.0) {
            return String.format("%.6g", re);
        }
        return String.format("%.6g%+.6gi", re, im);
    }
}
