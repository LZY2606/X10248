package com.polarlab;

import org.apache.commons.math3.complex.Complex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MuellerPhysicsTest {

    private static final double[][] H_POLARIZER = {
            {0.5, 0.5, 0.0, 0.0},
            {0.5, 0.5, 0.0, 0.0},
            {0.0, 0.0, 0.0, 0.0},
            {0.0, 0.0, 0.0, 0.0}
    };

    @Test
    void coherencyRoundTripOnHorizontalPolarizer() {
        Complex[][] t = Mueller.coherencyFromMueller(H_POLARIZER);
        double[] eig = Mat.eigHermitian(t);
        // rank-1 coherency: one positive eigenvalue, three ~0
        assertArrayEquals(new double[] {0, 0, 0, 0.5}, eig, 1e-9);
        double[][] back = Mueller.muellerFromCoherency(t);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(H_POLARIZER[i], back[i], 1e-9);
        }
    }

    @Test
    void identityIsPhysical() {
        assertTrue(Mueller.isPhysical(Mueller.identity4(), 1e-9));
        double[] eig = Mueller.coherencyEigenvalues(Mueller.identity4());
        assertArrayEquals(new double[] {0, 0, 0, 1}, eig, 1e-9);
    }

    @Test
    void overOneM00ViolationIsFlaggedNotHidden() {
        double[][] bad = Mueller.identity4().clone();
        bad[0][1] = 1.5; // |M01| > M00
        var violations = Mueller.physicalViolations(bad, 1e-9);
        assertTrue(violations.stream().anyMatch(v -> v.constraint().contains("M01") && !v.ok()));
        assertFalse(Mueller.isPhysical(bad, 1e-9));
        // the offending value itself is retained
        assertEquals(1.5, bad[0][1], 0);
    }

    @Test
    void negativeCoherencyEigenvalueIsDetected() {
        // a Mueller-like matrix that passes simple bounds but is not physically realizable
        double[][] bad = {
                {1, 0, 0, 0},
                {0, 1.2, 0, 0},
                {0, 0, 1.2, 0},
                {0, 0, 0, -1.2}
        };
        double[] eig = Mueller.coherencyEigenvalues(bad);
        assertTrue(eig[0] < -1e-6, "expected negative eigenvalue, got " + java.util.Arrays.toString(eig));
        assertFalse(Mueller.isPhysical(bad, 1e-9));
    }

    @Test
    void conventionsChangeStateVectors() {
        PolarState ccw = PolarState.linear(45, PolarState.Order.S0123, PolarState.RotSign.POS_CCW, "g");
        PolarState cw = PolarState.linear(45, PolarState.Order.S0123, PolarState.RotSign.POS_CW, "g");
        assertArrayEquals(new double[] {1, 0, 1, 0}, ccw.canonical(), 1e-12);
        assertArrayEquals(new double[] {1, 0, -1, 0}, cw.canonical(), 1e-12);

        PolarState perm = PolarState.linear(45, PolarState.Order.S0S2S1S3, PolarState.RotSign.POS_CCW, "g");
        // stored order permuted, canonical view identical
        assertArrayEquals(ccw.canonical(), perm.canonical(), 1e-12);
        assertEquals(1.0, perm.s[1], 1e-12); // S2 sits in slot 1 of the stored encoding
    }
}
