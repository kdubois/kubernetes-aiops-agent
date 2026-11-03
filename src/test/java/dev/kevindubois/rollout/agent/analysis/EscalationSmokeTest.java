package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in smoke test of the strong-model escalation tier with the real models:
 * the cheap tier analyzes a hard labelled case, the escalation tier re-analyzes
 * the same data with the strong model, and the grader grades both. Run it when
 * changing the escalation wiring or the strong model:
 *
 * <pre>ANALYSIS_API_KEY=... ANALYSIS_BASE_URL=... ANALYSIS_MODEL=... \
 * ANALYSIS_STRONG_BASE_URL=... ANALYSIS_STRONG_API_KEY=... ANALYSIS_STRONG_MODEL=... \
 * TYPESAFE_API_KEY=... mvn test -Dtest=EscalationSmokeTest -Descalation.smoke=true</pre>
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "escalation.smoke", matches = "true")
class EscalationSmokeTest {

    @Inject
    ChatModel chatModel;

    @Inject
    AnalysisGrader grader;

    @Inject
    AnalysisEscalation escalation;

    @Test
    void strongTierReanalyzesAndGrades() {
        assertTrue(escalation.enabled(), "the escalation tier must be configured for this test");

        // the memory-leak case: hard enough that the tiers can genuinely differ
        AnalysisEvalCases.EvalCase c = AnalysisEvalCases.load().stream()
                .filter(x -> x.name().equals("memory-leak-in-canary"))
                .findFirst().orElseThrow();
        String data = AnalysisEvalCases.diagnosticData(c);

        AnalysisAgent cheapAgent = AgenticServices.agentBuilder(AnalysisAgent.class)
                .chatModel(chatModel)
                .build();
        AnalysisResult cheap = cheapAgent.analyze(data, c.notes());
        assertNotNull(cheap, "the cheap tier returned no result");

        AnalysisResult strong = escalation.escalate(data, c.notes());
        assertNotNull(strong, "the strong tier returned no result");

        var cheapGrade = grader.grade(cheap, c.diagnosticReport(), c.rolloutReport(), c.metricsReport());
        var strongGrade = grader.grade(strong, c.diagnosticReport(), c.rolloutReport(), c.metricsReport());
        Log.infof("Smoke: cheap=%s (conf %d, grade %s), strong=%s (conf %d, grade %s)",
                cheap.promote() ? "promote" : "rollback", cheap.confidence(), cheapGrade,
                strong.promote() ? "promote" : "rollback", strong.confidence(), strongGrade);

        // both tiers must produce a well-formed conclusion for the labelled case
        List.of(cheap, strong).forEach(result -> {
            assertTrue(result.promote() == Boolean.TRUE.equals(c.expected().promote()),
                    "tier concluded " + (result.promote() ? "promote" : "rollback")
                            + " but the case labels " + (c.expected().promote() ? "promote" : "rollback"));
        });
    }
}
