package io.polroom;

/** Pauli matrices used to build the Cloude coherency matrix. */
final class Pauli {
    private Pauli() {}

    static double[][] s0() { return new double[][] {{1, 0}, {0, 1}}; }
    static double[][] s1() { return new double[][] {{1, 0}, {0, -1}}; }
    static double[][] s2() { return new double[][] {{0, 1}, {1, 0}}; }
    static double[][] s3() { return new double[][] {{0, -1}, {1, 0}}; }
}
