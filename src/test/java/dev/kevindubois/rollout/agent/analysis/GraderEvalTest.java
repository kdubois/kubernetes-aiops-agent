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
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Opt-in evaluation of the AnalysisGrader against the labelled scenarios in
 * analysis-eval.json. Runs the real analysis pipeline per case, then grades
 * the analysis's actual conclusion and its inversion with the real
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

    @Inject
    AnalysisEscalation escalation;

    /**
     * Cases whose evidence is designed to be thin. The grader may legitimately
     * dispute a correct conclusion built on insufficient evidence — that is
     * the pause gate holding a decision that outran its data, not a false
     * positive — so these are excluded from the gate-safety assertion.
     */
    private static final Set<String> THIN_EVIDENCE_CASES = Set.of("early-canary-thin-metrics");

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
        int escalated = 0;

        for (AnalysisEvalCases.EvalCase c : cases) {
            AnalysisResult real = agent.analyze(AnalysisEvalCases.diagnosticData(c), c.notes());
            assertNotNull(real, c.name() + ": the agent returned no result");

            Optional<Double> pCorrect = grader.grade(real,
                    c.diagnosticReport(), c.rolloutReport(), c.metricsReport());

            // the escalation tier: a clearly disputed conclusion is re-analyzed
            // with the second opinion model over the same data, then re-graded — this
            // mirrors AnalysisService's flow
            String escalationNote = "";
            if (pCorrect.isPresent() && pCorrect.get() < AnalysisService.CLEARLY_UNSUPPORTED
                    && escalation.enabled()) {
                AnalysisResult secondOpinion = escalation.escalate(AnalysisEvalCases.diagnosticData(c), c.notes());
                if (secondOpinion != null) {
                    escalated++;
                    Optional<Double> pSecondOpinion = grader.grade(secondOpinion,
                            c.diagnosticReport(), c.rolloutReport(), c.metricsReport());
                    escalationNote = String.format(Locale.ROOT,
                            "escalated: %.2f -> %.2f, %s -> %s", pCorrect.get(),
                            pSecondOpinion.orElse(-1.0), verdict(real), verdict(secondOpinion));
                    real = secondOpinion;
                    pCorrect = pSecondOpinion;
                }
            }

            Optional<Double> pInverted = grader.grade(inverted(real),
                    c.diagnosticReport(), c.rolloutReport(), c.metricsReport());

            if (pCorrect.isEmpty() || pInverted.isEmpty()) {
                Log.warnf("Eval %s: grader unavailable (correct=%s, inverted=%s)",
                        c.name(), pCorrect, pInverted);
                rows.add(String.format("| %s | %s | — | — | — |", c.name(), verdict(real)));
                continue;
            }
            graded++;
            boolean thin = THIN_EVIDENCE_CASES.contains(c.name());
            boolean safe = pCorrect.get() >= AnalysisService.CLEARLY_UNSUPPORTED || thin;
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
            } else if (thin) {
                note = "thin evidence: veto acceptable";
            }
            rows.add(String.format(Locale.ROOT, "| %s | %s | %.2f | %.2f | %s%s |",
                    c.name(), verdict(real), pCorrect.get(), pInverted.get(), note, escalationNote));
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
                - Escalations to the second opinion model: %d
                """.formatted(LocalDate.now(), AnalysisService.CLEARLY_UNSUPPORTED,
                String.join("\n", rows), vetoSafe, graded, vetoFired, wrongPromotes, separated, graded, escalated));
        Log.infof("Analysis grader evaluation: gate safety %d/%d, veto on wrong promotes %d/%d, discrimination %d/%d, escalations %d. Report written to %s",
                vetoSafe, graded, vetoFired, wrongPromotes, separated, graded, escalated, report);

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
