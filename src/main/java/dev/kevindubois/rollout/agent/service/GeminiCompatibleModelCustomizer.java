package dev.kevindubois.rollout.agent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Nullifies frequency_penalty and presence_penalty before the OpenAI model is built.
 * The Quarkus LangChain4j extension always sends these with a default of 0, but
 * Gemini 2.5 models reject unknown fields via the OpenAI-compatible endpoint.
 * Setting null prevents serialization; OpenAI ignores null (uses its own default of 0).
 */
@ApplicationScoped
public class GeminiCompatibleModelCustomizer implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        builder.frequencyPenalty(null).presencePenalty(null);
    }
}
