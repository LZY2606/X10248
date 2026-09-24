package pol.mat;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MathLayerTest {

    private static RealMatrix rotator(double angleRad) {
        double c = Math.cos(2 * angleRad);
        double s = Math.sin(2 * angleRad);
        return M.of(new double[][]{
                {1, 0, 0, 0},
                {0, c, -s, 0},
                {0, s, c, 0},
                {0, 0, 0, 1}
        });
    }

    private static RealMatrix linearDiattenuator(double d) {
        double q = Math.sqrt(1 - d * d);
        return M.of(new double[][]{
                {1, d, 0, 0},
                {d, 1, 0, 0},
                {0, 0, q, 0},
                {0, 0, 0, q}
        });
    }

    @Test
    void coherencyRoundTripsIdentityAndPhysicalMueller() {
        double[] ev = Coherency.eigenvalues(MatrixUtils.createRealIdentityMatrix(4));
        assertEquals(1.0, ev[0], 1e-9);
        for (int k = 1; k < 4; k++) {
            assertEquals(0.0, Math.abs(ev[k]), 1e-9);
        }
        RealMatrix m = rotator(0.35);
        RealMatrix back = Coherency.toMueller(Coherency.fromMueller(m));
        assertEquals(0, m.subtract(back).getFrobeniusNorm(), 1e-9);
        double[] evr = Coherency.eigenvalues(m);
        assertEquals(1.0, evr[0], 1e-9);
        assertEquals(0.0, Math.abs(evr[1]), 1e-9);
    }

    @Test
    void physicalRotatorAndDiattenuatorPassAudit() {
        assertTrue(Physicality.audit(rotator(0.7)).physical());
        assertTrue(Physicality.audit(linearDiattenuator(0.4)).physical());
    }

    @Test
    void nonPhysicalMuellerIsFlaggedNotProjected() {
        RealMatrix bad = M.of(new double[][]{
                {1, 0, 0, 0},
                {0, -1.6, 0, 0},
                {0, 0, 0.9, 0},
                {0, 0, 0, 0.9}
        });
        Physicality.Report report = Physicality.audit(bad);
        assertFalse(report.physical());
        assertTrue(report.violations.stream().anyMatch(v -> v.checkId.equals("Cloude-PSD"))
                || report.violations.stream().anyMatch(v -> v.checkId.equals("Bounds-Mij")));
        assertTrue(report.coherencyEigenvalues[3] < -Physicality.TOLERANCE);
    }

    @Test
    void luChipmanReconstructsProduct() {
        RealMatrix m = rotator(0.6);
        List<Decomposition.Candidate> cs = Decomposition.luChipman(m);
        Decomposition.Candidate c = cs.get(0);
        assertTrue(c.diagnostics.isEmpty(), c.diagnostics.toString());
        assertEquals(3, c.factors.size());
        RealMatrix product = c.factors.get(2).matrix
                .multiply(c.factors.get(1).matrix)
                .multiply(c.factors.get(0).matrix);
        assertEquals(0, product.subtract(m).getFrobeniusNorm(), 1e-8);
    }

    @Test
    void cloudeDecompositionReconstructs() {
        RealMatrix m = M.of(new double[][]{
                {0.9, 0.05, -0.02, 0.01},
                {0.04, 0.72, -0.03, 0.02},
                {-0.01, 0.02, 0.65, -0.05},
                {0.00, -0.01, 0.04, 0.55}
        });
        List<Decomposition.Candidate> cs = Decomposition.cloude(m);
        RealMatrix sum = MatrixUtils.createRealMatrix(4, 4);
        for (Decomposition.Factor f : cs.get(0).factors) {
            sum = sum.add(f.matrix);
        }
        assertEquals(0, sum.subtract(m).getFrobeniusNorm(), 1e-8);
    }

    @Test
    void fullRankDesignRecoversKnownMueller() {
        RealMatrix truth = M.of(new double[][]{
                {1.0, 0.1, -0.05, 0.02},
                {0.08, 0.80, 0.03, -0.04},
                {-0.02, 0.01, 0.75, 0.02},
                {0.01, -0.02, 0.03, 0.70}
        });
        double[] angles = {0, Math.PI / 6, Math.PI / 4, Math.PI / 3,
                Math.PI / 2, 2 * Math.PI / 3, 3 * Math.PI / 4, 5 * Math.PI / 6};
        double[] qwp = {Math.PI / 8, -Math.PI / 8, 3 * Math.PI / 8, -3 * Math.PI / 8};
        List<double[]> g = new ArrayList<>();
        List<double[]> a = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (double ag : angles) {
            for (double aa : angles) {
                double[] gs = OpticalStates.linearPolarizer(ag, 1, "SIHV");
                double[] as = OpticalStates.linearPolarizer(aa, 1, "SIHV");
                g.add(gs);
                a.add(as);
                y.add(M.dot(as, M.matVec(truth, gs)));
            }
        }
        for (double ag : qwp) {
            for (double aa : qwp) {
                double[] gs = OpticalStates.quarterWavePlate(ag, 1, "SIHV");
                double[] as = OpticalStates.linearPolarizer(aa, 1, "SIHV");
                g.add(gs);
                a.add(as);
                y.add(M.dot(as, M.matVec(truth, gs)));
            }
        }
        double[] yy = y.stream().mapToDouble(Double::doubleValue).toArray();
        Estimator.Result r = Estimator.estimate(
                new Estimator.Input(g, a, yy,
                        MatrixUtils.createRealIdentityMatrix(yy.length).getData()));
        assertEquals("OK", r.status);
        assertEquals(0, r.mueller.subtract(truth).getFrobeniusNorm(), 1e-7);
        assertTrue(r.conditionNumber < 1e4);
    }

    @Test
    void rankDeficientDesignIsNotInverted() {
        List<double[]> g = new ArrayList<>();
        List<double[]> a = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            double t = k * Math.PI / 8;
            double[] gs = OpticalStates.linearPolarizer(t, 1, "SIHV");
            double[] as = OpticalStates.linearPolarizer(t, 1, "SIHV");
            g.add(gs);
            a.add(as);
            y.add(1.0);
        }
        double[] yy = y.stream().mapToDouble(Double::doubleValue).toArray();
        Estimator.Result r = Estimator.estimate(
                new Estimator.Input(g, a, yy,
                        MatrixUtils.createRealIdentityMatrix(yy.length).getData()));
        assertEquals("RANK_DEFICIENT", r.status);
        assertNull(r.mueller);
        assertFalse(r.nullSpace.isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, r.conditionNumber);
    }
}
