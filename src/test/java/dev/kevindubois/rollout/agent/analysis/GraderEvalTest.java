package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Opt-in evaluation of the AnalysisGrader against the labelled scenarios in
 * analysis-eval.json. Runs the real analysis pipeline per case, then grades
 * the analysis's actual conclusion and its inversion with the real Jev
 * decision model, using the production state builder, and reports both
 * probabilities with their separation.
 *
 * Two properties are asserted:
 * <ul>
 *   <li>gate safety — a correct conclusion grades above 0.5, so a well-evidenced
 *       analysis does not trip the pause gate</li>
 *   <li>discrimination — the correct conclusion grades higher than its
 *       inversion, so the grader distinguishes supported from unsupported</li>
 * </ul>
 *
 * The normal suite never runs this because it calls the live chat and decision
 * models:
 *
 * <pre>ANALYSIS_API_KEY=... ANALYSIS_BASE_URL=... ANALYSIS_MODEL=... TYPESAFE_API_KEY=... \
 *   mvn test -Dtest=GraderEvalTest -Dgrader.eval=true</pre>
 *
 * Use it to compare grader state designs: change AnalysisGrader's state
 * builder, re-run, and read the separation table in
 * target/grader-eval-report.md.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "grader.eval", matches = "true")
class GraderEvalTest {

    @Inject
    ChatModel chatModel;

    @Inject
    AnalysisGrader grader;

    @Test
    void measureGraderSeparation() throws Exception {
        List<AnalysisEvalCases.EvalCase> cases = AnalysisEvalCases.load();
        AnalysisAgent agent = AgenticServices.agentBuilder(AnalysisAgent.class)
                .chatModel(chatModel)
                .build();

        List<String> rows = new ArrayList<>();
        int graded = 0;
        int vetoSafe = 0;
        int wrongPromotes = 0;
        int vetoFired = 0;
        int separated = 0;

        for (AnalysisEvalCases.EvalCase c : cases) {
            AnalysisResult real = agent.analyze(AnalysisEvalCases.diagnosticData(c), c.notes());
            assertNotNull(real, c.name() + ": the agent returned no result");

            Optional<Double> pCorrect = grader.grade(real,
                    c.diagnosticReport(), c.rolloutReport(), c.metricsReport());
            Optional<Double> pInverted = grader.grade(inverted(real),
                    c.diagnosticReport(), c.rolloutReport(), c.metricsReport());

            if (pCorrect.isEmpty() || pInverted.isEmpty()) {
                Log.warnf("Eval %s: grader unavailable (correct=%s, inverted=%s)",
                        c.name(), pCorrect, pInverted);
                rows.add(String.format("| %s | %s | — | — | — |", c.name(), verdict(real)));
                continue;
            }
            graded++;
            boolean safe = pCorrect.get() >= AnalysisService.CLEARLY_UNSUPPORTED;
            vetoSafe += safe ? 1 : 0;
            separated += pCorrect.get() > pInverted.get() ? 1 : 0;
            String note = "";
            if (!real.promote()) {
                // the analysis rolled back, so the inversion is a wrong promote:
                // the veto must fire on it
                wrongPromotes++;
                boolean fired = pInverted.get() < AnalysisService.CLEARLY_UNSUPPORTED;
                vetoFired += fired ? 1 : 0;
                note = fired ? "veto fires on the wrong promote" : "VETO MISSED";
            } else if (!safe) {
                note = "VETO FALSELY FIRES";
            }
            rows.add(String.format(Locale.ROOT, "| %s | %s | %.2f | %.2f | %s |",
                    c.name(), verdict(real), pCorrect.get(), pInverted.get(), note));
        }

        Path report = Path.of("target", "grader-eval-report.md");
        if (Files.notExists(report.getParent())) {
            Files.createDirectories(report.getParent());
        }
        Files.writeString(report, """
                # Analysis grader evaluation

                Grading the real analysis conclusions — %s. Veto bar: %.2f

                | Case | Analysis | P(correct) | P(inverted) | Veto |
                |---|---|---|---|---|
                %s

                - Gate safety (P(correct) >= veto bar): %d/%d
                - Veto fires on wrong promotes: %d/%d
                - Discrimination (P(correct) > P(inverted)): %d/%d (informational)
                """.formatted(LocalDate.now(), AnalysisService.CLEARLY_UNSUPPORTED,
                String.join("\n", rows), vetoSafe, graded, vetoFired, wrongPromotes, separated, graded));
        Log.infof("Analysis grader evaluation: gate safety %d/%d, veto on wrong promotes %d/%d, discrimination %d/%d. Report written to %s",
                vetoSafe, graded, vetoFired, wrongPromotes, separated, graded, report);

        assertThat(graded).as("the grader graded every case").isEqualTo(cases.size());
        assertThat(vetoSafe).as("a correct conclusion does not trip the veto").isEqualTo(graded);
        assertThat(vetoFired).as("the veto fires on every wrong promote").isEqualTo(wrongPromotes);
    }

    private static String verdict(AnalysisResult result) {
        return result.promote() ? "promote" : "rollback";
    }

    /**
     * The counterpart an incorrect analysis would have produced: the opposite
     * decision with a generic story, everything else (confidence, evidence
     * fields) carried over from the real conclusion.
     */
    private static AnalysisResult inverted(AnalysisResult real) {
        boolean promote = !real.promote();
        return new AnalysisResult(promote, real.confidence(), real.analysis(),
                promote ? "No issues" : "Canary shows problems",
                promote ? "Promote" : "Rollback",
                promote ? "Canary is healthy and comparable to stable" : "Canary shows problems; rollback",
                promote ? IssueCategory.NO_ISSUES : IssueCategory.OPERATIONAL,
                real.suspectClasses());
    }
}
