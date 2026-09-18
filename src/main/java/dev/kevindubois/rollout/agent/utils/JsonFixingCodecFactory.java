package dev.kevindubois.rollout.agent.utils;

import dev.kevindubois.rollout.agent.remediation.RemediationResult;
import dev.kevindubois.rollout.agent.remediation.RemediationOutcomeHolder;
import dev.langchain4j.internal.Json;
import io.quarkiverse.langchain4j.QuarkusJsonCodecFactory;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.lang.reflect.Type;

/**
 * Custom JSON codec factory that fixes common JSON formatting errors before parsing.
 * Also provides fallback construction of {@link RemediationResult} when the LLM
 * fails to produce parseable JSON (e.g. puts the response in reasoning_content).
 */
@ApplicationScoped
public class JsonFixingCodecFactory {

    private final JsonFixingOutputParser jsonFixer = new JsonFixingOutputParser();
    private final QuarkusJsonCodecFactory delegate = new QuarkusJsonCodecFactory();

    @Inject
    RemediationOutcomeHolder outcomeHolder;

    @Produces
    @ApplicationScoped
    public Json.JsonCodec createCodec() {
        return new JsonFixingCodec(delegate.create());
    }

    private RemediationResult tryRecoverRemediation(Type type) {
        if (type != RemediationResult.class) {
            return null;
        }
        return outcomeHolder.getOutcome()
                .map(r -> {
                    Log.warn("Recovered RemediationResult from tool outcome (LLM produced unparseable output)");
                    return r;
                })
                .orElse(null);
    }

    private class JsonFixingCodec implements Json.JsonCodec {
        private final Json.JsonCodec delegate;

        JsonFixingCodec(Json.JsonCodec delegate) {
            this.delegate = delegate;
        }

        @Override
        public String toJson(Object o) {
            return delegate.toJson(o);
        }

        @Override
        public <T> T fromJson(String json, Class<T> type) {
            return fromJsonInternal(json, type);
        }

        @Override
        public <T> T fromJson(String json, Type type) {
            return fromJsonInternal(json, type);
        }

        @SuppressWarnings("unchecked")
        private <T> T fromJsonInternal(String json, Type type) {
            try {
                String fixedJson = jsonFixer.fixJson(json);
                Log.debug("Fixed JSON for parsing (first 200 chars): " + fixedJson.substring(0, Math.min(200, fixedJson.length())));
                return type instanceof Class<?> c ? delegate.fromJson(fixedJson, (Class<T>) c) : delegate.fromJson(fixedJson, type);
            } catch (Exception e) {
                RemediationResult recovered = tryRecoverRemediation(type);
                if (recovered != null) {
                    return (T) recovered;
                }
                Log.error("JSON parsing failed even after fixes. Original: " +
                        json.substring(0, Math.min(500, json.length())));
                throw e;
            }
        }
    }
}
