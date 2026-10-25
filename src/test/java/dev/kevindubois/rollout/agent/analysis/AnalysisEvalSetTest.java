package dev.kevindubois.rollout.agent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the structure of the labelled evaluation set. Always runs; calls no model.
 */
class AnalysisEvalSetTest {

    @Test
    void evalCasesAreWellFormed() {
        List<AnalysisEvalCases.EvalCase> cases = AnalysisEvalCases.load();

        assertFalse(cases.isEmpty(), "the evaluation set must contain at least one case");

        for (AnalysisEvalCases.EvalCase evalCase : cases) {
            String name = evalCase.name();
            assertFalse(name.isBlank(), "every case needs a name");

            assertTrue(evalCase.diagnosticReport().startsWith("=== LOG DIAGNOSTIC REPORT ==="),
                    name + ": diagnosticReport must match the DiagnosticsDataAgent format");
            assertTrue(evalCase.rolloutReport().startsWith("=== ROLLOUT STATUS REPORT ==="),
                    name + ": rolloutReport must match the RolloutStatusDataAgent format");
            assertTrue(evalCase.metricsReport().startsWith("=== METRICS REPORT ==="),
                    name + ": metricsReport must match the MetricsDataAgent format");

            AnalysisEvalCases.Expected expected = evalCase.expected();
            assertNotNull(expected, name + ": expected block is required");
            assertNotNull(expected.promote(), name + ": promote expectation is required");

            if (expected.issueCategory() != null) {
                // Must parse as a valid IssueCategory
                IssueCategory.valueOf(expected.issueCategory());
            }
            if (expected.minConfidence() != null) {
                assertTrue(expected.minConfidence() >= 0 && expected.minConfidence() <= 100,
                        name + ": minConfidence must be 0-100");
            }
        }
    }

    @Test
    void evalCoversCoreScenarios() {
        List<String> names = AnalysisEvalCases.load().stream()
                .map(AnalysisEvalCases.EvalCase::name)
                .toList();

        // The four demo scenario outcomes plus the early-canary edge case
        assertTrue(names.contains("healthy-canary"));
        assertTrue(names.contains("npe-in-canary"));
        assertTrue(names.contains("memory-leak-in-canary"));
        assertTrue(names.contains("latency-drift-in-canary"));
        assertTrue(names.stream().anyMatch(n -> n.startsWith("early-canary")));
    }
}
