package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Opt-in evaluation of the AnalysisAgent against the labelled scenarios in
 * analysis-eval.json. Calls the configured chat model once per case, compares the
 * result against the labels, and writes a report to target/analysis-eval-report.md.
 *
 * The normal suite never runs this because it calls the live model. Run it with:
 *
 * ANALYSIS_API_KEY=... ANALYSIS_BASE_URL=... ANALYSIS_MODEL=... \
 *   mvn test -Dtest=AnalysisEvalTest -Danalysis.eval=true
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "analysis.eval", matches = "true")
class AnalysisEvalTest {

    @Inject
    ChatModel chatModel;

    @Inject
    Config config;

    @Test
    void measureAnalysisAgreement() throws Exception {
        List<AnalysisEvalCases.EvalCase> cases = AnalysisEvalCases.load();
        AnalysisAgent agent = AgenticServices.agentBuilder(AnalysisAgent.class)
                .chatModel(chatModel)
                .build();

        String model = config.getOptionalValue("quarkus.langchain4j.openai.chat-model.model-name", String.class)
                .orElse("unknown");

        int promoteOk = 0;
        int categoryChecked = 0;
        int categoryOk = 0;
        int confidenceChecked = 0;
        int confidenceOk = 0;
        int rootCauseChecked = 0;
        int rootCauseOk = 0;
        int passed = 0;

        List<String> rows = new ArrayList<>();

        for (AnalysisEvalCases.EvalCase evalCase : cases) {
            String name = evalCase.name();
            AnalysisResult result = agent.analyze(AnalysisEvalCases.diagnosticData(evalCase));
            assertNotNull(result, name + ": the agent returned no result");

            AnalysisEvalCases.Expected expected = evalCase.expected();

            boolean promoteMatch = expected.promote().equals(result.promote());
            if (promoteMatch) {
                promoteOk++;
            }

            boolean categoryMatch = true;
            if (expected.issueCategory() != null) {
                categoryChecked++;
                categoryMatch = result.issueCategory() != null
                        && result.issueCategory().name().equals(expected.issueCategory());
                if (categoryMatch) {
                    categoryOk++;
                }
            }

            boolean confidenceMatch = true;
            if (expected.minConfidence() != null) {
                confidenceChecked++;
                confidenceMatch = result.confidence() >= expected.minConfidence();
                if (confidenceMatch) {
                    confidenceOk++;
                }
            }

            boolean rootCauseMatch = true;
            if (expected.rootCauseContains() != null) {
                rootCauseChecked++;
                rootCauseMatch = result.rootCause() != null && result.rootCause()
                        .toLowerCase(Locale.ROOT)
                        .contains(expected.rootCauseContains().toLowerCase(Locale.ROOT));
                if (rootCauseMatch) {
                    rootCauseOk++;
                }
            }

            boolean verdict = promoteMatch && categoryMatch && confidenceMatch && rootCauseMatch;
            if (verdict) {
                passed++;
            }

            Log.infof("Eval %s: %s (promote=%s/%s, category=%s, confidence=%s, rootCause=%s)",
                    name, verdict ? "PASS" : "FAIL",
                    result.promote(), expected.promote(),
                    result.issueCategory(), result.confidence(), result.rootCause());

            rows.add(String.format("| %s | %s (%s) | %s | %s | %s | %s |",
                    name,
                    promoteMatch ? "✓" : "✗", result.promote(),
                    expected.issueCategory() != null
                            ? (categoryMatch ? "✓ " : "✗ ") + result.issueCategory()
                            : "—",
                    expected.minConfidence() != null
                            ? (confidenceMatch ? "✓ " : "✗ ") + result.confidence()
                            : "—",
                    expected.rootCauseContains() != null
                            ? (rootCauseMatch ? "✓" : "✗")
                            : "—",
                    verdict ? "PASS" : "FAIL"));
        }

        StringBuilder report = new StringBuilder();
        report.append("# Analysis pipeline evaluation\n\n");
        report.append("Model: ").append(model).append(" — ").append(LocalDate.now()).append("\n\n");
        report.append("| Case | Promote | Category | Confidence | Root cause | Verdict |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        rows.forEach(report::append);
        report.append("\n## Agreement\n\n");
        report.append("- Promote: ").append(promoteOk).append("/").append(cases.size()).append("\n");
        report.append("- Category: ").append(categoryOk).append("/").append(categoryChecked).append(" checked\n");
        report.append("- Confidence within range: ").append(confidenceOk).append("/").append(confidenceChecked)
                .append(" checked\n");
        report.append("- Root cause mentions: ").append(rootCauseOk).append("/").append(rootCauseChecked)
                .append(" checked\n");
        report.append("- Cases fully passing: ").append(passed).append("/").append(cases.size()).append("\n");

        Path reportPath = Path.of("target/analysis-eval-report.md");
        Files.writeString(reportPath, report.toString());

        Log.infof("Analysis evaluation: %d/%d cases passed. Report written to %s",
                passed, cases.size(), reportPath.toAbsolutePath());
    }
}
