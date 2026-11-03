package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;

import java.time.Duration;

/**
 * Strong-model escalation tier for the analysis, in the shape of langchain4j's
 * Confidence Cascade: the default (cheaper) model analyzes first, the grader
 * checks the conclusion, and a clearly disputed conclusion is re-analyzed once
 * with a stronger model over the same diagnostic data. If the escalated
 * conclusion is still disputed, the pause gate takes over — the human is the
 * final tier.
 *
 * <p>Configured through {@code analysis.escalation.*} (fed by the
 * {@code ANALYSIS_STRONG_*} environment variables). The reference
 * deployment runs the cheap tier on Qwen (litemaas) and the strong tier on
 * DeepSeek (enmaas) — both open-weights models — with the escalation decided
 * by the Jev grader. An empty model name disables the tier: a disputed
 * conclusion then goes straight to the pause gate, exactly as before this
 * tier existed.
 *
 * <p>Known limitation: the declarative sequence's remediation step acts on the
 * pre-escalation conclusion. When the escalation flips the direction of the
 * decision, the rollout decision is correct (it is built after the escalation)
 * but the remediation side effect may be spurious or missing. The full fix is
 * to move the remediation trigger behind the escalation; not done yet because
 * the race needs a disputed conclusion, a direction flip and a configured
 * repository at the same time.
 */
@ApplicationScoped
public class AnalysisEscalation {

    private final ChatModel strongModel;
    private final AnalysisAgent strongAgent;

    @jakarta.inject.Inject
    AnalysisEscalation(Config config) {
        this(strongModel(config));
    }

    AnalysisEscalation(ChatModel strongModel) {
        this.strongModel = strongModel;
        this.strongAgent = strongModel == null ? null
                : AgenticServices.agentBuilder(AnalysisAgent.class)
                        .chatModel(strongModel)
                        .build();
        if (strongModel == null) {
            Log.info("Analysis escalation disabled (no ANALYSIS_STRONG_MODEL): a disputed conclusion "
                    + "goes straight to the pause gate");
        } else {
            Log.infof("Analysis escalation enabled: disputed conclusions are re-analyzed with %s",
                    strongModel.toString());
        }
    }

    private static ChatModel strongModel(Config config) {
        String modelName = config.getOptionalValue("analysis.escalation.model", String.class).orElse("").trim();
        if (modelName.isEmpty()) {
            return null;
        }
        String baseUrl = config.getOptionalValue("analysis.escalation.base-url", String.class).orElse("");
        String apiKey = config.getOptionalValue("analysis.escalation.api-key", String.class).orElse("");
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.3)
                // reasoning models spend tokens on thinking before the answer
                .maxCompletionTokens(12288)
                .timeout(Duration.ofSeconds(120))
                .responseFormat("json")
                .build();
    }

    public boolean enabled() {
        return strongAgent != null;
    }

    /**
     * Re-analyzes the same diagnostic data with the strong model.
     *
     * @param diagnosticData the combined diagnostic report the disputed
     *                       conclusion was based on
     * @param prompt         the operator notes forwarded with the request
     * @return the escalated conclusion, or null when the strong model fails
     */
    public AnalysisResult escalate(String diagnosticData, String prompt) {
        try {
            return strongAgent.analyze(diagnosticData, prompt != null ? prompt : "");
        } catch (RuntimeException e) {
            Log.warnf("Analysis escalation failed (%s); keeping the disputed conclusion", e.getMessage());
            return null;
        }
    }
}
