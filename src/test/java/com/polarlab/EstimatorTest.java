package com.polarlab;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EstimatorTest {

    static final double[][] TRUE_M = CaseData.TRUE_MUELLER;

    static List<Estimator.Row> rowsFor(List<CaseData.Optic> optics, double dark, double gain,
                                       double noiseSigma, long seed, boolean singular) {
        return CaseData.synthesize(optics, dark, gain, noiseSigma, seed, singular).stream()
                .map(c -> new Estimator.Row(c.id(),
                        state(c.analyzer()), state(c.generator()),
                        c.raw(), dark, gain, c.var(), 0.01, 0.0))
                .toList();
    }

    private static double[] state(String id) {
        // mirror CaseData state construction
        if (id.startsWith("GQ") || id.startsWith("AQ")) {
            double a = id.endsWith("0") ? 0 : 45;
            return PolarState.quarterWave(a, PolarState.Order.S0123, PolarState.RotSign.POS_CCW, id).canonical();
        }
        String num = id.substring(1);
        int k = Integer.parseInt(num);
        return PolarState.linear(180.0 * k / 8, PolarState.Order.S0123, PolarState.RotSign.POS_CCW, id).canonical();
    }

    @Test
    void fullRankRecoversTrueMueller() {
        var optics = CaseData.gridStates(8);
        var res = Estimator.estimate(rowsFor(optics, 12, 1, 0.9, 20260924L, false), 1e-8);
        assertEquals("OK", res.status());
        assertEquals(16, res.rank());
        assertEquals(0, res.nullity());
        assertTrue(res.cond() < 1e6, "cond=" + res.cond());
        double[][] m = res.mueller();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                assertEquals(TRUE_M[i][j], m[i][j], 0.02, "M" + i + j);
            }
        }
        assertNotNull(res.cov());
    }

    @Test
    void singularDesignIsNotInvertedAndKeepsFamily() {
        var optics = CaseData.gridStates(8);
        var res = Estimator.estimate(rowsFor(optics, 12, 1, 0.4, 7711L, true), 1e-8);
        assertEquals("SINGULAR", res.status());
        assertEquals(15, res.rank());
        assertEquals(1, res.nullity());
        assertNull(res.solution(), "no unique solution claimed");
        assertNull(res.cov(), "no covariance for a non-unique inverse");
        assertTrue(res.candidates().size() >= 2, "must retain multiple legal family candidates");
        assertTrue(res.candidates().stream().anyMatch(c -> c.kind().contains("family-1d")));
        // the minimum-norm point must be explicitly labeled as diagnostic only
        assertTrue(res.candidates().get(0).note().toLowerCase().contains("not claimed"));
    }

    @Test
    void nearSingularThresholdTriggersRefusal() {
        var optics = CaseData.gridStates(8);
        var healthy = Estimator.estimate(rowsFor(optics, 12, 1, 0.9, 20260924L, false), 1e-8, 1e10);
        // an absurdly small threshold classifies the same well-posed problem as near-singular
        var fussy = Estimator.estimate(rowsFor(optics, 12, 1, 0.9, 20260924L, false), 1e-8, 1e-2);
        assertEquals("OK", healthy.status());
        assertEquals("NEAR_SINGULAR", fussy.status());
        assertNull(fussy.solution());
    }

    @Test
    void nonPhysicalNoiseIsFlaggedNotProjected() {
        var optics = CaseData.gridStates(8);
        var doc = CaseData.injectNonphysical(CaseData.intensityDoc(
                CaseData.synthesize(optics, 12, 1, 0.9, 20260924L, false)), 4242L);
        List<Estimator.Row> rows = new ArrayList<>();
        int idx = 0;
        for (var r : doc.get("rows")) {
            rows.add(new Estimator.Row(r.get("id").asText(),
                    state(r.get("analyzerId").asText()), state(r.get("generatorId").asText()),
                    r.get("value").get("rawValue").asDouble(), 12, 1,
                    r.get("variance").asDouble(), 0.01, 0));
            idx++;
        }
        var res = Estimator.estimate(rows, 1e-8);
        assertEquals("OK_NONPHYSICAL", res.status());
        var cand = res.candidates().get(0);
        assertFalse(cand.physical());
        assertTrue(cand.violations().stream().anyMatch(v -> !v.ok()));
        assertTrue(cand.note().contains("NON-PHYSICAL"));
    }

    @Test
    void correlatedReferenceUncertaintyEntersCovariance() {
        var optics = CaseData.gridStates(8);
        var noGain = CaseData.synthesize(optics, 12, 1, 0.0, 99L, false).stream()
                .map(c -> new Estimator.Row(c.id(), state(c.analyzer()), state(c.generator()),
                        c.raw(), 12, 1, 1e-12, 1e-12, 0.0)).toList();
        var withGain = noGain.stream().map(r -> new Estimator.Row(r.measurementId(), r.analyzerCanon(),
                r.generatorCanon(), r.raw(), r.dark(), r.gain(), r.rawVar(), r.darkVar(), 0.05)).toList();
        var r1 = Estimator.estimate(noGain, 1e-8);
        var r2 = Estimator.estimate(withGain, 1e-8);
        double t1 = 0, t2 = 0;
        for (int i = 0; i < 16; i++) {
            t1 += r1.cov().getEntry(i, i);
            t2 += r2.cov().getEntry(i, i);
        }
        assertTrue(t2 > t1, "reference gain uncertainty must inflate propagated variance");
    }
}
