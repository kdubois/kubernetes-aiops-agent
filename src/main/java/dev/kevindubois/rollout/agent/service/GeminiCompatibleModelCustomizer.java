package dev.kevindubois.rollout.agent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Strips parameters that non-OpenAI endpoints reject before the model is built.
 *
 * frequency_penalty / presence_penalty: The Quarkus LangChain4j extension always
 * sends these with a default of 0; Gemini and LiteLLM proxies reject them as
 * unknown fields. Setting null prevents serialization.
 *
 * reasoning_effort=none: Disables Gemini's "thinking" mode. With thinking on,
 * Gemini attaches a thought_signature to function-call parts and requires it to be
 * echoed back on the next turn. LangChain4j's OpenAI client has no support for
 * round-tripping that field, so multi-step tool use fails with
 * "Function call is missing a thought_signature" (400 INVALID_ARGUMENT).
 * Only applied when the base URL points to a Gemini endpoint, because other models
 * (e.g. Qwen via LiteLLM) reject reasoning_effort as an unsupported parameter.
 *
 * Qualified with both @Default and @ModelName("remediation") so it applies to
 * both the default analysis model and the named remediation model.
 */
@ApplicationScoped
@Default
@ModelName("remediation")
public class GeminiCompatibleModelCustomizer implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

    @ConfigProperty(name = "quarkus.langchain4j.openai.base-url", defaultValue = "")
    String baseUrl;

    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        builder.frequencyPenalty(null).presencePenalty(null);
        if (baseUrl.contains("generativelanguage.googleapis.com")) {
            builder.reasoningEffort("none");
        }
    }
}
