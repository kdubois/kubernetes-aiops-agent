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
    void disabledWhenMapIsNull() {
        PauseGate gate = PauseGate.fromMap(null);
        assertFalse(gate.enabled());
        assertFalse(gate.shouldPause(result(true, 10)));
    }

    @Test
    void disabledWhenFlagAbsent() {
        PauseGate gate = PauseGate.fromMap(Map.of("namespace", "quarkus-demo"));
        assertFalse(gate.enabled());
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

        assertTrue(gate.shouldPause(result(true, 69)), "promote below the threshold pauses");
        assertFalse(gate.shouldPause(result(true, 70)), "confidence equal to the threshold does not pause");
        assertFalse(gate.shouldPause(result(true, 95)), "high confidence does not pause");
        assertFalse(gate.shouldPause(result(false, 40)), "a rollback aborts the rollout, nothing to pause");
    }
}
