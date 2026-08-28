package dev.kevindubois.rollout.agent.a2a;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import dev.kevindubois.rollout.agent.remediation.GitOperations;
import dev.kevindubois.rollout.agent.remediation.RepoCloneCache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the analysis pipeline.
 * Exercises the full path from REST request through the multi-agent workflow
 * (K8s data gathering → LLM analysis → scoring → remediation) using
 * WireMock for the LLM and GitHub APIs and a mocked K8sTools for cluster data.
 */
@QuarkusTest
@QuarkusTestResource(value = WireMockExtension.class, restrictToAnnotatedClass = true)
class AnalysisPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final RestAssuredConfig REST_CONFIG = RestAssuredConfig.config()
            .objectMapperConfig(ObjectMapperConfig.objectMapperConfig()
                    .jackson2ObjectMapperFactory((type, s) -> LENIENT_MAPPER));
    private static final String NAMESPACE = "test-ns";

    @InjectMock
    K8sTools k8sTools;

    @InjectMock
    RepoCloneCache repoCloneCache;

    @InjectMock
    GitOperations gitOperations;

    @BeforeEach
    void setUp() {
        WireMockExtension.server.resetAll();
    }

    // ── Scenarios ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Healthy canary with good metrics → promote")
    void healthyCanary_shouldPromote() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(PROMOTE_ANALYSIS, ACCEPT_SCORING);

        var response = postAnalyze(NAMESPACE, null);

        assertThat(response.promote()).isTrue();
        assertThat(response.confidence()).isGreaterThanOrEqualTo(80);
    }

    @Test
    @DisplayName("NullPointerException in canary → rollback (CODE_BUG) + GitHub PR created")
    void npeInCanary_shouldRollbackAndCreatePR(@TempDir Path tempRepo) throws Exception {
        Path srcDir = tempRepo.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("GreetingService.java"), GREETING_SERVICE_SOURCE);

        stubK8s(npeDiagnostics(), npeMetrics());
        stubLlm(NPE_ANALYSIS, ACCEPT_SCORING);
        when(repoCloneCache.getOrClone(any(), any())).thenReturn(tempRepo);

        stubGitHubFileContent("src/main/java/com/example/GreetingService.java",
                GREETING_SERVICE_SOURCE);
        stubGitHubRepository();
        stubGitHubPRCreation();
        stubRemediationLlm();

        var response = postAnalyze(NAMESPACE, "https://github.com/test-owner/test-repo");

        assertThat(response.promote()).isFalse();
        assertThat(response.confidence()).isGreaterThanOrEqualTo(70);
        assertThat(response.rootCause()).containsIgnoringCase("NullPointerException");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                WireMockExtension.server.verify(
                        postRequestedFor(urlPathEqualTo("/repos/test-owner/test-repo/pulls"))));
    }

    @Test
    @DisplayName("OutOfMemoryError in canary → rollback (OPERATIONAL) + GitHub issue created")
    void memoryLeak_shouldRollbackAndCreateGitHubIssue() {
        stubK8s(memoryLeakDiagnostics(), memoryLeakMetrics());
        stubLlm(MEMORY_LEAK_ANALYSIS, ACCEPT_SCORING);
        stubGitHubIssueCreation();

        var response = postAnalyze(NAMESPACE, "https://github.com/test-owner/test-repo");

        assertThat(response.promote()).isFalse();
        assertThat(response.confidence()).isGreaterThanOrEqualTo(70);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                WireMockExtension.server.verify(
                        postRequestedFor(urlPathEqualTo("/repos/test-owner/test-repo/issues"))));
    }

    // ── K8s mock setup ─────────────────────────────────────────────────

    private void stubK8s(Map<String, Object> diagnostics, Map<String, Object> metrics) {
        when(k8sTools.getCanaryDiagnostics(NAMESPACE, null, 200)).thenReturn(diagnostics);
        when(k8sTools.getCanaryMetrics(NAMESPACE)).thenReturn(metrics);
    }

    // ── WireMock stubs ─────────────────────────────────────────────────

    private void stubLlm(String analysisJson, String scoringJson) {
        var wm = WireMockExtension.server;

        wm.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("K8s SRE analysis"))
                .willReturn(okJson(chatCompletion(analysisJson))));

        wm.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("scoring agent"))
                .willReturn(okJson(chatCompletion(scoringJson))));
    }

    private void stubGitHubIssueCreation() {
        WireMockExtension.server.stubFor(post(urlPathMatching("/repos/.+/.+/issues"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"number":1,\
                                "html_url":"https://github.com/test-owner/test-repo/issues/1",\
                                "state":"open",\
                                "title":"Canary Deployment Failed"}""")));
    }

    private void stubGitHubFileContent(String filePath, String content) {
        String base64Content = Base64.getEncoder().encodeToString(content.getBytes());
        String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
        WireMockExtension.server.stubFor(get(urlPathEqualTo(
                "/repos/test-owner/test-repo/contents/" + filePath))
                .willReturn(okJson(String.format(
                        "{\"name\":\"%s\",\"path\":\"%s\",\"sha\":\"abc123\"," +
                        "\"size\":%d,\"type\":\"file\"," +
                        "\"content\":\"%s\",\"encoding\":\"base64\"}",
                        fileName, filePath, content.length(), base64Content))));
    }

    private void stubGitHubRepository() {
        WireMockExtension.server.stubFor(get(urlPathEqualTo("/repos/test-owner/test-repo"))
                .willReturn(okJson(
                        "{\"name\":\"test-repo\",\"full_name\":\"test-owner/test-repo\"," +
                        "\"default_branch\":\"main\"," +
                        "\"html_url\":\"https://github.com/test-owner/test-repo\"}")));
    }

    private void stubGitHubPRCreation() {
        WireMockExtension.server.stubFor(post(urlPathEqualTo("/repos/test-owner/test-repo/pulls"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"number\":42," +
                                "\"html_url\":\"https://github.com/test-owner/test-repo/pull/42\"," +
                                "\"state\":\"open\"," +
                                "\"title\":\"Fix: Null-guard greet() to prevent NPE\"}")));
    }

    private void stubRemediationLlm() {
        var wm = WireMockExtension.server;

        wm.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("remediation")
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(containing("Remediation agent"))
                .willReturn(okJson(toolCallCompletion("createGitHubPRWithPatches",
                        remediationToolArgs())))
                .willSetStateTo("TOOL_EXECUTED"));

        wm.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("remediation")
                .whenScenarioStateIs("TOOL_EXECUTED")
                .withRequestBody(containing("Remediation agent"))
                .willReturn(okJson(chatCompletion(REMEDIATION_RESULT))));
    }

    // ── HTTP request helper ────────────────────────────────────────────

    private KubernetesAgentResponse postAnalyze(String namespace, String repoUrl) {
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", namespace);
        if (repoUrl != null) {
            context.put("repoUrl", repoUrl);
            context.put("baseBranch", "main");
        }

        return given()
                .config(REST_CONFIG)
                .contentType("application/json")
                .body(Map.of(
                        "userId", "test-user",
                        "prompt", "Analyze canary deployment health",
                        "context", context))
                .when()
                .post("/a2a/analyze")
                .then()
                .statusCode(200)
                .extract()
                .as(KubernetesAgentResponse.class);
    }

    // ── OpenAI response builders ───────────────────────────────────────

    private static String chatCompletion(String content) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "id", "chatcmpl-test",
                    "object", "chat.completion",
                    "created", 1234567890,
                    "model", "gpt-4o",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "message", Map.of("role", "assistant", "content", content),
                            "finish_reason", "stop")),
                    "usage", Map.of(
                            "prompt_tokens", 100,
                            "completion_tokens", 50,
                            "total_tokens", 150)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toolCallCompletion(String toolName, String arguments) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("role", "assistant");
            message.put("content", null);
            message.put("tool_calls", List.of(Map.of(
                    "id", "call_test123",
                    "type", "function",
                    "function", Map.of(
                            "name", toolName,
                            "arguments", arguments))));

            return MAPPER.writeValueAsString(Map.of(
                    "id", "chatcmpl-toolcall",
                    "object", "chat.completion",
                    "created", 1234567890,
                    "model", "gpt-4o",
                    "choices", List.of(Map.of(
                            "index", 0,
                            "message", message,
                            "finish_reason", "tool_calls")),
                    "usage", Map.of(
                            "prompt_tokens", 100,
                            "completion_tokens", 50,
                            "total_tokens", 150)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String remediationToolArgs() {
        try {
            String patches = MAPPER.writeValueAsString(List.of(Map.of(
                    "filePath", "src/main/java/com/example/GreetingService.java",
                    "changes", List.of(Map.of(
                            "lineNumber", 9,
                            "action", "replace",
                            "content", "        String greeting = \"Hello, \" + (name != null ? name.toUpperCase() : \"World\") + \"!\";")))));

            return MAPPER.writeValueAsString(Map.ofEntries(
                    Map.entry("repoUrl", "https://github.com/test-owner/test-repo"),
                    Map.entry("patches", patches),
                    Map.entry("title", "Null-guard greet() to prevent NPE"),
                    Map.entry("fixDescription", "Added null check for name parameter"),
                    Map.entry("rootCause", "NullPointerException when name is null"),
                    Map.entry("namespace", "test-ns"),
                    Map.entry("podName", "app-canary-abc"),
                    Map.entry("testingRecommendations", "Test with null name")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── K8s diagnostics fixtures ───────────────────────────────────────

    private static Map<String, Object> healthyDiagnostics() {
        return diagnostics(
                podData("app-stable-xyz", "Running", "1/1",
                        "INFO Application started successfully\n"
                                + "INFO Processing requests normally\n"
                                + "INFO Request completed in 45ms"),
                podData("app-canary-abc", "Running", "1/1",
                        "INFO Application started successfully\n"
                                + "INFO Processing requests normally\n"
                                + "INFO Request completed in 48ms"));
    }

    private static Map<String, Object> npeDiagnostics() {
        return diagnostics(
                podData("app-stable-xyz", "Running", "1/1",
                        "INFO Application started successfully\n"
                                + "INFO Processing requests normally"),
                podData("app-canary-abc", "Running", "1/1",
                        """
                                INFO Application started
                                ERROR java.lang.NullPointerException: Cannot invoke "String.length()" because "str" is null
                                    at com.example.GreetingService.greet(GreetingService.java:42)
                                    at com.example.GreetingResource.hello(GreetingResource.java:18)
                                ERROR java.lang.NullPointerException: Cannot invoke "String.length()" because "str" is null
                                    at com.example.GreetingService.greet(GreetingService.java:42)"""));
    }

    private static Map<String, Object> memoryLeakDiagnostics() {
        return diagnostics(
                podData("app-stable-xyz", "Running", "1/1",
                        "INFO Application started successfully\n"
                                + "INFO Processing requests normally"),
                podData("app-canary-abc", "Running", "1/1",
                        """
                                INFO Application started
                                WARN Heap usage above 80%: 450MB / 512MB
                                WARN GC overhead limit approaching - consecutive full GCs detected
                                ERROR java.lang.OutOfMemoryError: Java heap space
                                    at java.util.ArrayList.grow(ArrayList.java:265)
                                    at java.util.ArrayList.add(ArrayList.java:462)
                                ERROR java.lang.OutOfMemoryError: Java heap space
                                WARN Pod memory approaching limit: 480MB / 512MB"""));
    }

    // ── K8s metrics fixtures ───────────────────────────────────────────

    private static Map<String, Object> healthyMetrics() {
        return metrics(
                metricsData(10000, 99.5, 0.5, 45.0, 120.0, null, null, null),
                metricsData(2000, 99.2, 0.8, 48.0, 125.0, null, null, null));
    }

    private static Map<String, Object> npeMetrics() {
        return metrics(
                metricsData(10000, 99.5, 0.5, 45.0, 120.0, null, null, null),
                metricsData(2000, 82.0, 18.0, 250.0, 800.0, null, null, null));
    }

    private static Map<String, Object> memoryLeakMetrics() {
        return metrics(
                metricsData(10000, 99.5, 0.5, 45.0, 120.0, 200.0, 512.0, 15.0),
                metricsData(2000, 75.0, 25.0, 500.0, 2000.0, 480.0, 512.0, 250.0));
    }

    // ── Data builders ──────────────────────────────────────────────────

    private static Map<String, Object> diagnostics(Map<String, Object> stable,
                                                    Map<String, Object> canary) {
        var m = new HashMap<String, Object>();
        m.put("namespace", NAMESPACE);
        m.put("stable", stable);
        m.put("canary", canary);
        return m;
    }

    private static Map<String, Object> metrics(Map<String, Object> stable,
                                                Map<String, Object> canary) {
        var m = new HashMap<String, Object>();
        m.put("namespace", NAMESPACE);
        m.put("stable", stable);
        m.put("canary", canary);
        return m;
    }

    private static Map<String, Object> podData(String name, String phase,
                                                String ready, String logs) {
        var m = new HashMap<String, Object>();
        m.put("podName", name);
        m.put("phase", phase);
        m.put("readyContainers", ready);
        m.put("logs", logs);
        return m;
    }

    private static Map<String, Object> metricsData(double totalRequests,
                                                    double successRate, double errorRate,
                                                    double p95, double p99,
                                                    Double heapUsedMb, Double heapMaxMb,
                                                    Double gcCount) {
        var m = new HashMap<String, Object>();
        m.put("totalRequests", totalRequests);
        m.put("calculatedSuccessRate", successRate);
        m.put("errorRate", errorRate);
        m.put("latencyP95Ms", p95);
        m.put("latencyP99Ms", p99);
        if (heapUsedMb != null) m.put("heapUsedMb", heapUsedMb);
        if (heapMaxMb != null) m.put("heapMaxMb", heapMaxMb);
        if (gcCount != null) m.put("gcCount", gcCount);
        return m;
    }

    // ── Canned LLM payloads ────────────────────────────────────────────

    private static final String PROMOTE_ANALYSIS = """
            {"promote":true,"confidence":95,\
            "analysis":"Both stable and canary pods healthy. Error rates within threshold (0.8% vs 0.5%). Latency within acceptable range.",\
            "rootCause":"No issues detected",\
            "remediation":"Promote canary to stable",\
            "summary":"Canary healthy - promote",\
            "issueCategory":"NO_ISSUES",\
            "suspectClasses":[]}""";

    private static final String NPE_ANALYSIS = """
            {"promote":false,"confidence":92,\
            "analysis":"NullPointerException in canary GreetingService.greet(). Error rate 18% vs stable 0.5%.",\
            "rootCause":"NullPointerException in GreetingService.greet() at line 42",\
            "remediation":"Add null check in GreetingService.greet() before accessing str parameter",\
            "summary":"NPE in canary GreetingService - rollback",\
            "issueCategory":"CODE_BUG",\
            "suspectClasses":["GreetingService"]}""";

    private static final String MEMORY_LEAK_ANALYSIS = """
            {"promote":false,"confidence":88,\
            "analysis":"OutOfMemoryError in canary. Heap at 480MB/512MB, GC count 250 vs stable 15. Error rate 25%.",\
            "rootCause":"java.lang.OutOfMemoryError: Java heap space - memory leak causing heap exhaustion",\
            "remediation":"Increase memory limits or investigate memory leak in application",\
            "summary":"OOM in canary - memory leak - rollback",\
            "issueCategory":"OPERATIONAL",\
            "suspectClasses":[]}""";

    private static final String ACCEPT_SCORING = """
            {"score":90,"needsRetry":false,"reason":"Analysis quality acceptable"}""";

    private static final String REMEDIATION_RESULT = """
            {"prLink":"https://github.com/test-owner/test-repo/pull/42",\
            "remediation":"Added null check for name parameter in GreetingService.greet()"}""";

    private static final String GREETING_SERVICE_SOURCE = """
            package com.example;

            import jakarta.enterprise.context.ApplicationScoped;

            @ApplicationScoped
            public class GreetingService {

                public String greet(String name) {
                    String greeting = "Hello, " + name.toUpperCase() + "!";
                    return greeting;
                }
            }
            """;
}
