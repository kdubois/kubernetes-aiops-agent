package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.utils.TextUtils;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.Optional;

/**
 * Independent second opinion on a completed analysis. Asks the Jev decision
 * model one yes/no question — is the analysis conclusion fully supported by
 * the diagnostic data? — and returns the yes-probability. The pause gate
 * combines it conservatively with the analysis LLM's self-reported confidence:
 * effective confidence = min(self-reported, probability × 100).
 *
 * <p>Deliberately a single decision call with a threshold (grade → combine →
 * gate), shaped like the Confidence Cascade pattern langchain4j-agentic
 * provides since 1.23.0 (ConfidenceCascadePlanner). Our gate escalates to a
 * human pause instead of a stronger agent tier, so the cascade planner itself
 * does not fit here — but keeping this shape means swapping to a tiered-model
 * cascade later is a wiring change, not a redesign.
 *
 * <p>The grader must never fail the measurement: without a configured
 * TYPESAFE_API_KEY, on a call failure, or when the model returns no answer it
 * reports empty and the caller falls back to the self-reported confidence.
 */
@ApplicationScoped
public class AnalysisGrader {

    private static final String QUESTION_ID = "conclusion_supported";
    private static final String QUESTION =
            "Is this analysis conclusion fully supported by the diagnostic data?";

    /**
     * Budgets inside the grader state. The metrics and rollout reports are the
     * quantitative basis of every conclusion and are small, so they are kept
     * whole; the log diagnostics are an excerpt; the conclusion always
     * survives at the end.
     */
    private static final int MAX_METRICS_LENGTH = 2000;
    private static final int MAX_ROLLOUT_LENGTH = 1000;
    private static final int MAX_DIAGNOSTIC_LENGTH = 3000;
    private static final int MAX_SUMMARY_LENGTH = 200;
    private static final int MAX_ROOT_CAUSE_LENGTH = 500;

    private final DecisionModel decisionModel;
    private final boolean enabled;

    @Inject
    AnalysisGrader(@ModelName("jev") DecisionModel decisionModel, Config config) {
        this(decisionModel, keyConfigured(config));
    }

    AnalysisGrader(DecisionModel decisionModel, boolean enabled) {
        this.decisionModel = decisionModel;
        this.enabled = enabled;
        if (!enabled) {
            Log.info("Analysis grader disabled (no TYPESAFE_API_KEY): "
                    + "the uncertainty gate uses the self-reported confidence");
        }
    }

    private static boolean keyConfigured(Config config) {
        String apiKey = config.getOptionalValue("quarkus.langchain4j.typesafe.jev.api-key", String.class).orElse("");
        // "missing" is the sentinel that lets the decision model bean initialize without a key
        return !apiKey.isBlank() && !"missing".equals(apiKey);
    }

    /**
     * Grades a completed analysis against the evidence it was based on.
     *
     * @param result           the analysis conclusion to grade
     * @param diagnosticReport pod and log diagnostics from the workflow
     * @param rolloutReport    rollout phase, step, and traffic weights
     * @param metricsReport    application metrics (error rates, latency)
     * @return the yes-probability in [0, 1] that the conclusion is supported
     *         by the evidence, or empty when the grader is disabled or failed
     */
    public Optional<Double> grade(AnalysisResult result, String diagnosticReport, String rolloutReport,
            String metricsReport) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            DecisionResponse response = decisionModel.decide(DecisionRequest.builder()
                    .input(state(result, diagnosticReport, rolloutReport, metricsReport))
                    .question(QUESTION_ID, YesNoQuestion.from(QUESTION))
                    .build());
            YesNoAnswer answer = response.yesNo(QUESTION_ID);
            if (answer == null) {
                Log.warn("Analysis grader returned no answer; falling back to the self-reported confidence");
                return Optional.empty();
            }
            return Optional.of(answer.probability());
        } catch (RuntimeException e) {
            Log.warnf("Analysis grader call failed (%s); falling back to the self-reported confidence", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Condensed grader state: the metrics and rollout reports first — they are
     * the quantitative basis of every conclusion and are small enough to keep
     * whole — then an excerpt of the log diagnostics, then the conclusion.
     * Evidence-first ordering matters: truncating the combined workflow report
     * from the front once cut the metrics out entirely, and the grader
     * correctly doubted every conclusion whose evidence it never saw.
     */
    private String state(AnalysisResult result, String diagnosticReport, String rolloutReport,
            String metricsReport) {
        return "Metrics:\n"
                + TextUtils.truncate(metricsReport, MAX_METRICS_LENGTH)
                + "\n\nRollout status:\n"
                + TextUtils.truncate(rolloutReport, MAX_ROLLOUT_LENGTH)
                + "\n\nLog diagnostics (excerpt):\n"
                + TextUtils.truncate(diagnosticReport, MAX_DIAGNOSTIC_LENGTH)
                + "\n\nAnalysis conclusion:\n"
                + "- decision: " + (result.promote() ? "PROMOTE" : "ROLLBACK") + "\n"
                + "- confidence: " + result.confidence() + "/100\n"
                + "- summary: " + TextUtils.truncate(result.summary(), MAX_SUMMARY_LENGTH) + "\n"
                + "- root cause: " + TextUtils.truncate(result.rootCause(), MAX_ROOT_CAUSE_LENGTH);
    }
}
