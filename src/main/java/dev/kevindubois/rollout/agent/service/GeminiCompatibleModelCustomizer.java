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
 * Also disables Gemini's "thinking" mode via reasoning_effort=none. With thinking
 * on, Gemini attaches a thought_signature to function-call parts and requires it
 * to be echoed back on the next turn; LangChain4j's OpenAI client has no support
 * for round-tripping that field (its sendThinking/returnThinking options are for
 * DeepSeek-style reasoning_content text, not Gemini's opaque signature), so any
 * multi-step tool use (e.g. RemediationAgent's fetch-then-createGitHubPRWithPatches
 * flow) fails with "Function call is missing a thought_signature" (400
 * INVALID_ARGUMENT) on the second tool call. Disabling thinking means Gemini never
 * requires a signature in the first place.
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
        builder.frequencyPenalty(null).presencePenalty(null).reasoningEffort("none");
    }
}
