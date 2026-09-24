package pol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ejml.data.DMatrixRMaj;
import org.junit.jupiter.api.Test;

import pol.decomp.CloudeSpectral;
import pol.decomp.Decomposition;
import pol.decomp.ForwardLuChipman;
import pol.decomp.ReverseOssikovski;
import pol.domain.Mtx;
import pol.domain.MuellerBlocks;
import pol.domain.PhysicalCheck;

class MathCoreTest {

    @Test
    void physicalMuellerPassesConstraintsAndDecompositionsReconstruct() {
        double[] axisQ = {1, 0, 0};
        DMatrixRMaj diattenuator = MuellerBlocks.diattenuator(new double[] {0.25, 0, 0});
        DMatrixRMaj retarder = MuellerBlocks.retarder(axisQ, Math.toRadians(60));
        // diagonal depolarizer: eigen-values [1, 0.9, 0.8, 0.7] normalized
        DMatrixRMaj depol = Mtx.mat(4, 4,
                1, 0, 0, 0,
                0, 0.9, 0, 0,
                0, 0, 0.8, 0,
                0, 0, 0, 0.7);
        DMatrixRMaj m = Mtx.multiply(depol, Mtx.multiply(retarder, diattenuator));

        PhysicalCheck.Result check = PhysicalCheck.check(m);
        assertTrue(check.physicallyRealizable(), () -> "physical matrix flagged: " + check.violations());
        assertTrue(check.minEigenvalue() >= -1e-9);
        assertEquals(1.0, check.eigenvalueSum(), 1e-9);
        assertTrue(check.degreeOfPolarimetricPurity() <= 1.0 + 1e-9);

        Decomposition fwd = ForwardLuChipman.decompose(m);
        assertEquals(0.0, fwd.relativeResidual(), 1e-8);
        Decomposition rev = ReverseOssikovski.decompose(m);
        assertEquals(0.0, rev.relativeResidual(), 1e-6);
        Decomposition cloude = CloudeSpectral.decompose(m);
        assertEquals(0.0, cloude.relativeResidual(), 1e-9);
        assertTrue(cloude.valid());
    }

    @Test
    void nonPhysicalMuellerIsFlaggedNotProjected() {
        DMatrixRMaj bad = Mtx.mat(4, 4,
                1.0, 0.9, 0.0, 0.0,
                0.0, 1.5, 0.0, 0.0,
                0.0, 0.0, 0.2, 0.0,
                0.0, 0.0, 0.0, 0.1);
        PhysicalCheck.Result check = PhysicalCheck.check(bad);
        assertFalse(check.physicallyRealizable());
        assertTrue(check.minEigenvalue() < 0);
        Decomposition cloude = CloudeSpectral.decompose(bad);
        assertFalse(cloude.valid(), "non-physical candidate must stay flagged");
        assertTrue(cloude.note().contains("negative eigenvalue"));
        // candidate matrix itself is unchanged (no projection)
        assertEquals(1.5, cloude.m().get(1, 1), 0.0);
    }

    @Test
    void stokesReorderingMapsComponents() {
        pol.domain.StokesOrder iqvu = pol.domain.StokesOrder.of(java.util.List.of("I", "Q", "V", "U"));
        double[] canonical = iqvu.toIQUV(new double[] {1, 0.1, 0.2, 0.3});
        assertEquals(0.3, canonical[2], 1e-12, "U component");
        assertEquals(0.2, canonical[3], 1e-12, "V component");
        assertEquals("stokes=IQVU", iqvu.fingerprint());
    }

    @Test
    void rotationConventionsEnterFingerprintWithOppositeSign() {
        assertEquals(-1, pol.domain.RotationConvention.LEFT_HANDED.sign());
        assertEquals("rotation=RH", pol.domain.RotationConvention.RIGHT_HANDED.fingerprint());
        assertEquals("rotation=LH", pol.domain.RotationConvention.LEFT_HANDED.fingerprint());
    }
}
