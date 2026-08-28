package dev.kevindubois.rollout.agent.a2a;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Starts a WireMock server and redirects the LLM (OpenAI-compatible) and
 * GitHub REST client endpoints to it, so the full analysis pipeline can be
 * exercised without real external services.
 */
public class WireMockExtension implements QuarkusTestResourceLifecycleManager {

    static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();

        String baseUrl = server.baseUrl();
        return Map.of(
                "quarkus.langchain4j.openai.base-url", baseUrl + "/v1",
                "quarkus.langchain4j.openai.api-key", "test-key",
                "quarkus.langchain4j.openai.remediation.base-url", baseUrl + "/v1",
                "quarkus.langchain4j.openai.remediation.api-key", "test-key",
                "quarkus.rest-client.github-api.url", baseUrl,
                "github.token", "test-token"
        );
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
