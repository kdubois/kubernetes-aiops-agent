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

    /** Diagnostic-data budget inside the grader state; the conclusion follows it. */
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
     * Grades a completed analysis against the diagnostic data it was based on.
     *
     * @param result         the analysis conclusion to grade
     * @param diagnosticData the combined diagnostic report from the workflow
     * @return the yes-probability in [0, 1] that the conclusion is supported
     *         by the data, or empty when the grader is disabled or failed
     */
    public Optional<Double> grade(AnalysisResult result, String diagnosticData) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            DecisionResponse response = decisionModel.decide(DecisionRequest.builder()
                    .input(state(result, diagnosticData))
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
     * Condensed grader state: truncated diagnostic data followed by the
     * analysis conclusion. Kept well under the decision model's input limits —
     * the diagnostic data is truncated first so the conclusion always survives.
     */
    private String state(AnalysisResult result, String diagnosticData) {
        return "Diagnostic data:\n"
                + TextUtils.truncate(diagnosticData, MAX_DIAGNOSTIC_LENGTH)
                + "\n\nAnalysis conclusion:\n"
                + "- decision: " + (result.promote() ? "PROMOTE" : "ROLLBACK") + "\n"
                + "- confidence: " + result.confidence() + "/100\n"
                + "- summary: " + TextUtils.truncate(result.summary(), MAX_SUMMARY_LENGTH) + "\n"
                + "- root cause: " + TextUtils.truncate(result.rootCause(), MAX_ROOT_CAUSE_LENGTH);
    }
}
