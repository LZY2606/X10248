package io.polroom;

import org.apache.commons.math3.linear.RealMatrix;

/** A Mueller matrix together with the Stokes-order / rotation-sign conventions it was estimated under. */
public record Mueller(RealMatrix m, Conventions conventions) {
    public double m00() { return m.getEntry(0, 0); }

    public double depolarizationIndex() {
        double sumSq = 0;
        double[][] a = LA.arr(m);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) sumSq += a[i][j] * a[i][j];
        double base = a[0][0] == 0 ? 1.0 : a[0][0];
        return Math.sqrt(sumSq) / (Math.sqrt(3.0) * Math.abs(base));
    }

    /** Mueller matrices are conventionally quoted normalised by m00 (dimensionless transmittance). */
    public RealMatrix normalized() {
        double base = m.getEntry(0, 0) == 0 ? 1.0 : m.getEntry(0, 0);
        return m.scalarMultiply(1.0 / base);
    }
}
