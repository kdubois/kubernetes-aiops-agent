package dev.kevindubois.rollout.agent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

/**
 * Nullifies frequency_penalty and presence_penalty before the OpenAI model is built.
 * The Quarkus LangChain4j extension always sends these with a default of 0, but
 * Gemini 2.5 models reject unknown fields via the OpenAI-compatible endpoint.
 * Setting null prevents serialization; OpenAI ignores null (uses its own default of 0).
 *
 * Qualified with both @Default and @ModelName("remediation") so it applies to
 * both the default analysis model and the named remediation model.
 */
@ApplicationScoped
@Default
@ModelName("remediation")
public class GeminiCompatibleModelCustomizer implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        builder.frequencyPenalty(null).presencePenalty(null);
    }
}
