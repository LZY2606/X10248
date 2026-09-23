package com.polarlab.optics;

/** Stokes vectors in the configurable order [I, Q, U, V] (other orders handled by basis). */
public final class Stokes {
    private Stokes() {}

    public static double[] unpolarized(double i) {
        return new double[]{i, 0, 0, 0};
    }

    public static double[] linear(double theta, double i) {
        return new double[]{i, i * Math.cos(2 * theta), i * Math.sin(2 * theta), 0};
    }

    public static double[] circular(double handedV, double i) {
        return new double[]{i, 0, 0, handedV * i};
    }
}
