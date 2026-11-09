package dev.kevindubois.rollout.agent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseGateTest {

    private static AnalysisResult result(boolean promote, int confidence) {
        return new AnalysisResult(promote, confidence, "analysis", "rootCause",
                "remediation", "summary", IssueCategory.NO_ISSUES, List.of());
    }

    @Test
    void disabledWithoutTheFlagOrTheMap() {
        assertFalse(PauseGate.fromMap(null).enabled());
        assertFalse(PauseGate.fromMap(Map.of("namespace", "quarkus-demo")).enabled());
        assertFalse(PauseGate.fromMap(null).shouldPause(result(true, 10), 10));
    }

    @Test
    void parsesContext() {
        PauseGate gate = PauseGate.fromMap(Map.of(
                "pauseOnUncertainty", true,
                "uncertaintyThreshold", 70));
        assertTrue(gate.enabled());
        assertEquals(70, gate.threshold());
    }

    @Test
    void thresholdDefaultsToFifty() {
        PauseGate gate = PauseGate.fromMap(Map.of("pauseOnUncertainty", "true"));
        assertEquals(50, gate.threshold());
    }

    @Test
    void thresholdClampedAndInvalidFallsBack() {
        assertEquals(100, PauseGate.fromMap(Map.of("pauseOnUncertainty", true, "uncertaintyThreshold", 150)).threshold());
        assertEquals(0, PauseGate.fromMap(Map.of("pauseOnUncertainty", true, "uncertaintyThreshold", -5)).threshold());
        assertEquals(50, PauseGate.fromMap(Map.of("pauseOnUncertainty", true, "uncertaintyThreshold", "abc")).threshold());
    }

    @Test
    void pausesOnlyLowConfidencePromote() {
        PauseGate gate = new PauseGate(true, 70);

        assertTrue(gate.shouldPause(result(true, 69), 69), "promote below the threshold pauses");
        assertFalse(gate.shouldPause(result(true, 70), 70), "confidence equal to the threshold does not pause");
        assertFalse(gate.shouldPause(result(true, 95), 95), "high confidence does not pause");
        assertFalse(gate.shouldPause(result(false, 40), 40), "a rollback aborts the rollout, nothing to pause");
    }

    @Test
    void gateUsesTheGivenConfidenceNotTheSelfReportedOne() {
        PauseGate gate = new PauseGate(true, 70);

        // a grader second opinion lowered the effective confidence below the threshold
        assertTrue(gate.shouldPause(result(true, 90), 50), "effective confidence below the threshold pauses");
        // the grader confirmed the self-reported confidence: no pause
        assertFalse(gate.shouldPause(result(true, 60), 85), "effective confidence above the threshold does not pause");
    }
}
