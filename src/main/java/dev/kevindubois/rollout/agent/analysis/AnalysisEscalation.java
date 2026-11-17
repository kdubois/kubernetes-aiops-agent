package dev.kevindubois.rollout.agent.analysis;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Second-opinion escalation tier for the analysis, in the shape of langchain4j's
 * Confidence Cascade: the default (cheaper) model analyzes first, the grader
 * checks the conclusion, and a clearly disputed conclusion is re-analyzed once
 * by a second-opinion model over the same diagnostic data. If the escalated
 * conclusion is still disputed, the pause gate takes over — the human is the
 * final tier.
 *
 * <p>The second-opinion model is the named {@code secondopinion} OpenAI
 * configuration ({@code quarkus.langchain4j.openai.secondopinion.*}), fed by
 * the {@code ANALYSIS_SECOND_OPINION_*} environment variables and falling back
 * to the analysis credentials. The reference deployment runs the cheap tier on
 * Qwen (litemaas) and the second opinion tier on DeepSeek (enmaas) — both
 * open-weights models — with the escalation decided by the decision-model
 * grader. An unset model name disables the tier: a disputed conclusion then
 * goes straight to the pause gate, exactly as before this tier existed.
 */
@ApplicationScoped
public class AnalysisEscalation {

    /** The named model configuration behind this tier. */
    static final String SECOND_OPINION_MODEL_PROPERTY =
            "quarkus.langchain4j.openai.secondopinion.chat-model.model-name";

    private final SecondOpinionAnalysisAgent secondOpinionAgent;
    private final boolean enabled;

    @Inject
    AnalysisEscalation(Config config, SecondOpinionAnalysisAgent secondOpinionAgent) {
        this(secondOpinionAgent, modelConfigured(config));
    }

    AnalysisEscalation(SecondOpinionAnalysisAgent secondOpinionAgent, boolean enabled) {
        this.secondOpinionAgent = secondOpinionAgent;
        this.enabled = enabled;
        if (!enabled) {
            Log.info("Analysis escalation disabled (no ANALYSIS_SECOND_OPINION_MODEL): a disputed conclusion "
                    + "goes straight to the pause gate");
        } else {
            Log.info("Analysis escalation enabled: disputed conclusions are re-analyzed with the second-opinion model");
        }
    }

    /**
     * The tier is on when a second-opinion model is configured. The sentinel
     * model name keeps the named model bean usable while the tier is off.
     */
    private static boolean modelConfigured(Config config) {
        String modelName = config.getOptionalValue(SECOND_OPINION_MODEL_PROPERTY, String.class).orElse("").trim();
        return !modelName.isEmpty() && !"missing".equals(modelName);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Re-analyzes the same diagnostic data with the second-opinion model.
     *
     * @param diagnosticData the combined diagnostic report the disputed
     *                       conclusion was based on
     * @param prompt         the operator notes forwarded with the request
     * @return the escalated conclusion, or null when the second-opinion model fails
     */
    public AnalysisResult escalate(String diagnosticData, String prompt) {
        try {
            return secondOpinionAgent.analyze(diagnosticData, prompt != null ? prompt : "");
        } catch (RuntimeException e) {
            Log.warnf("Analysis escalation failed (%s); keeping the disputed conclusion", e.getMessage());
            return null;
        }
    }
}
