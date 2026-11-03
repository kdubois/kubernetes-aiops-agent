package dev.kevindubois.rollout.agent.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.kevindubois.rollout.agent.analysis.AnalysisEscalation;
import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.analysis.IssueCategory;
import dev.kevindubois.rollout.agent.analysis.AnalysisGrader;
import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.kevindubois.rollout.agent.k8s.RolloutStatusData;
import dev.kevindubois.rollout.agent.k8s.PodDataResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the analysis pipeline.
 * Exercises the full path from REST request through the multi-agent workflow
 * (K8s data gathering → LLM analysis → A2A remediation delegation)
 * using WireMock for the LLM and the remediation A2A agent and a mocked
 * K8sTools for cluster data.
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
    ActivityEvents activityEvents;

    @InjectMock
    AnalysisGrader analysisGrader;

    @InjectMock
    AnalysisEscalation analysisEscalation;

    @BeforeEach
    void setUp() {
        WireMockExtension.server.resetAll();
    }

    // ── Scenarios ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Healthy canary with good metrics → promote")
    void healthyCanary_shouldPromote() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(PROMOTE_ANALYSIS);

        var response = postAnalyze(NAMESPACE, null);

        assertThat(response.promote()).isTrue();
        assertThat(response.confidence()).isGreaterThanOrEqualTo(80);
        assertThat(response.pausedRollout()).isNull();
        verify(k8sTools, never()).pauseRollout(any(), any());
        // the request prompt reaches the model as operator notes
        WireMockExtension.server.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("Analyze canary deployment health")));
    }

    @Test
    @DisplayName("Low-confidence promote with the pause gate → rollout paused, measurement stays successful")
    void lowConfidencePromoteWithPauseGate_shouldPauseRollout() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(LOW_CONFIDENCE_PROMOTE);
        when(k8sTools.pauseRollout(NAMESPACE, null)).thenReturn("quarkus-demo");

        var response = postAnalyzeWithPauseGate(NAMESPACE, 70);

        assertThat(response.promote()).isTrue();
        assertThat(response.confidence()).isEqualTo(55);
        assertThat(response.pausedRollout()).isEqualTo("quarkus-demo");
        verify(k8sTools).pauseRollout(NAMESPACE, null);
    }

    @Test
    @DisplayName("Low-confidence promote without the pause gate → promote, no pause")
    void lowConfidencePromoteWithoutPauseGate_shouldNotPause() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(LOW_CONFIDENCE_PROMOTE);

        var response = postAnalyze(NAMESPACE, null);

        assertThat(response.promote()).isTrue();
        assertThat(response.pausedRollout()).isNull();
        verify(k8sTools, never()).pauseRollout(any(), any());
    }

    @Test
    @DisplayName("Grader agrees with a confident promote → effective confidence stays high, no pause")
    void graderAgrees_shouldNotPause() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(CONFIDENT_PROMOTE);
        when(analysisGrader.grade(any(), any(), any(), any())).thenReturn(Optional.of(0.95));

        var response = postAnalyzeWithPauseGate(NAMESPACE, 70);

        assertThat(response.promote()).isTrue();
        assertThat(response.confidence()).isEqualTo(80);
        assertThat(response.pausedRollout()).isNull();
        verify(k8sTools, never()).pauseRollout(any(), any());
        // the UI-facing confidence event reflects the effective confidence
        verify(activityEvents).confidenceScored(80, 80, "Canary healthy - promote");
    }

    @Test
    @DisplayName("Grader neutral on a confident promote → the self-reported confidence stands, no pause")
    void graderNeutral_shouldNotPause() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(CONFIDENT_PROMOTE);
        when(analysisGrader.grade(any(), any(), any(), any())).thenReturn(Optional.of(0.5));

        var response = postAnalyzeWithPauseGate(NAMESPACE, 70);

        assertThat(response.promote()).isTrue();
        assertThat(response.confidence()).isEqualTo(80);
        assertThat(response.pausedRollout()).isNull();
        verify(k8sTools, never()).pauseRollout(any(), any());
        // the neutral band does not lower the effective confidence
        verify(activityEvents).confidenceScored(80, 80, "Canary healthy - promote");
    }

    @Test
    @DisplayName("Grader clearly disputes a confident promote → effective confidence drops, rollout paused")
    void graderClearlyDisputes_shouldPause() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(CONFIDENT_PROMOTE);
        when(analysisGrader.grade(any(), any(), any(), any())).thenReturn(Optional.of(0.2));
        when(k8sTools.pauseRollout(NAMESPACE, null)).thenReturn("quarkus-demo");

        var response = postAnalyzeWithPauseGate(NAMESPACE, 70);

        assertThat(response.promote()).isTrue();
        // the response contract keeps the self-reported confidence
        assertThat(response.confidence()).isEqualTo(80);
        assertThat(response.pausedRollout()).isEqualTo("quarkus-demo");
        verify(k8sTools).pauseRollout(NAMESPACE, null);
        // the gate drove on the grader's confidence, not the self-reported one
        verify(activityEvents).confidenceScored(80, 20, "Canary healthy - promote");
        verify(activityEvents).analysisCompleted(any(), eq(20));
    }

    @Test
    @DisplayName("Grader disputes a promote, escalation flips it to a rollback → the escalated decision wins")
    void escalationFlipsDisputedPromote() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(CONFIDENT_PROMOTE);
        when(analysisGrader.grade(any(), any(), any(), any())).thenReturn(Optional.of(0.05));
        when(analysisEscalation.enabled()).thenReturn(true);
        AnalysisResult escalated = new AnalysisResult(false, 97, "escalated analysis",
                "Canary introduces a defect", "Rollback", "Strong model found a defect the cheap model missed",
                IssueCategory.CODE_BUG, List.of("MetricsResource"));
        when(analysisEscalation.escalate(any(), any())).thenReturn(escalated);

        var response = postAnalyze(NAMESPACE, null, null);

        assertThat(response.promote()).isFalse();
        assertThat(response.confidence()).isEqualTo(97);
        assertThat(response.rootCause()).isEqualTo("Canary introduces a defect");
        verify(activityEvents).analysisEscalated(eq(0.05), any(), eq(escalated));
        verify(k8sTools, never()).pauseRollout(any(), any());
    }

    @Test
    @DisplayName("Grader disputes, escalation fails → the disputed conclusion stands and gates as before")
    void escalationFailureKeepsDisputedConclusion() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(CONFIDENT_PROMOTE);
        when(analysisGrader.grade(any(), any(), any(), any())).thenReturn(Optional.of(0.2));
        when(analysisEscalation.enabled()).thenReturn(true);
        when(analysisEscalation.escalate(any(), any())).thenReturn(null);
        when(k8sTools.pauseRollout(NAMESPACE, null)).thenReturn("quarkus-demo");

        var response = postAnalyzeWithPauseGate(NAMESPACE, 70);

        assertThat(response.promote()).isTrue();
        assertThat(response.pausedRollout()).isEqualTo("quarkus-demo");
        verify(k8sTools).pauseRollout(NAMESPACE, null);
    }

    @Test
    @DisplayName("Grader unavailable → falls back to the self-reported confidence, no pause")
    void graderUnavailable_fallsBackToSelfReportedConfidence() {
        stubK8s(healthyDiagnostics(), healthyMetrics());
        stubLlm(CONFIDENT_PROMOTE);
        when(analysisGrader.grade(any(), any(), any(), any())).thenReturn(Optional.empty());

        var response = postAnalyzeWithPauseGate(NAMESPACE, 70);

        assertThat(response.promote()).isTrue();
        assertThat(response.confidence()).isEqualTo(80);
        assertThat(response.pausedRollout()).isNull();
        verify(k8sTools, never()).pauseRollout(any(), any());
        verify(activityEvents).confidenceScored(80, 80, "Canary healthy - promote");
    }

    @Test
    @DisplayName("NullPointerException in canary → rollback (CODE_BUG) + remediation delegated over A2A")
    void npeInCanary_shouldRollbackAndDelegateRemediation() {
        stubK8s(npeDiagnostics(), npeMetrics());
        stubLlm(NPE_ANALYSIS);
        stubRemediationAgent("""
                {"status":"success","prLink":"https://github.com/test-owner/test-repo/pull/42",\
                "issueLink":null,"branch":"fix/k8s-issue-ab12","filesChanged":2,\
                "testSummary":"Tests run: 10, Failures: 0","sandbox":"remediation-ab12",\
                "model":"test-model","message":null}""");

        var response = postAnalyze(NAMESPACE, "https://github.com/test-owner/test-repo");

        assertThat(response.promote()).isFalse();
        assertThat(response.confidence()).isGreaterThanOrEqualTo(70);
        assertThat(response.rootCause()).containsIgnoringCase("NullPointerException");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            WireMockExtension.server.verify(
                    postRequestedFor(urlPathEqualTo("/")));
            // the outcome reaches the activity events, not just the wire
            verify(activityEvents).remediationCompleted("https://github.com/test-owner/test-repo/pull/42");
        });
    }

    @Test
    @DisplayName("OutOfMemoryError in canary → rollback (OPERATIONAL) + remediation delegated over A2A")
    void memoryLeak_shouldRollbackAndDelegateRemediation() {
        stubK8s(memoryLeakDiagnostics(), memoryLeakMetrics());
        stubLlm(MEMORY_LEAK_ANALYSIS);
        stubRemediationAgent("""
                {"status":"issue","prLink":null,\
                "issueLink":"https://github.com/test-owner/test-repo/issues/1",\
                "branch":null,"filesChanged":0,"testSummary":null,"sandbox":null,\
                "model":null,"message":"Operational issue detected by canary analysis"}""");

        var response = postAnalyze(NAMESPACE, "https://github.com/test-owner/test-repo");

        assertThat(response.promote()).isFalse();
        assertThat(response.confidence()).isGreaterThanOrEqualTo(70);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            WireMockExtension.server.verify(
                    postRequestedFor(urlPathEqualTo("/")));
            // issue links pass the same validation as PR links
            verify(activityEvents).remediationCompleted("https://github.com/test-owner/test-repo/issues/1");
        });
    }

    @Test
    @DisplayName("LLM returns 429 on first call → retried → promote on second call")
    void rateLimitOnFirstCall_shouldRetryAndSucceed() {
        stubK8s(healthyDiagnostics(), healthyMetrics());

        var wm = WireMockExtension.server;
        wm.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("rate-limit-retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(containing("K8s SRE analysis"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"429 Too Many Requests. exceeded your current quota. Please retry in 1s.\",\"type\":\"rate_limit_error\"}}"))
                .willSetStateTo("RETRY"));

        wm.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("rate-limit-retry")
                .whenScenarioStateIs("RETRY")
                .withRequestBody(containing("K8s SRE analysis"))
                .willReturn(okJson(chatCompletion(PROMOTE_ANALYSIS))));

        var response = postAnalyze(NAMESPACE, null);

        assertThat(response.promote()).isTrue();
        wm.verify(2, postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("K8s SRE analysis")));
    }

    @Test
    @DisplayName("LLM returns a non-retryable error → workflow fails without outer retries")
    void nonRetryableError_shouldNotTriggerOuterRetry() {
        stubK8s(healthyDiagnostics(), healthyMetrics());

        // Stub the LLM to always return 400 — non-retryable error, so the
        // analysis fails on the single LLM call. SmallRye @Retry must NOT add an
        // outer retry on top of that — with maxRetries=3 an outer retry would
        // multiply to at least 2 LLM calls.
        WireMockExtension.server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("K8s SRE analysis"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"Invalid request\",\"type\":\"invalid_request_error\"}}")));

        given()
                .config(REST_CONFIG)
                .contentType("application/json")
                .body(Map.of(
                        "userId", "test-user",
                        "prompt", "Analyze canary deployment health",
                        "context", Map.of("namespace", NAMESPACE)))
                .when()
                .post("/analyze")
                .then()
                .statusCode(500);

        // Exactly one LLM call, no outer retry. If SmallRye retried, we would
        // see a multiple of that.
        WireMockExtension.server.verify(1,
                postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("K8s SRE analysis")));
    }

    @Test
    @DisplayName("Remediation agent times out → reported as a failed remediation, not a completed one")
    void remediationTimeout_shouldReportFailedRemediation() {
        stubK8s(npeDiagnostics(), npeMetrics());
        stubLlm(NPE_ANALYSIS);
        stubRemediationAgent("""
                {"status":"timeout","prLink":null,\
                "issueLink":null,"branch":null,"filesChanged":0,"testSummary":null,\
                "sandbox":"remediation-ab12","model":null,\
                "message":"Sandbox run exceeded the time limit"}""");

        var response = postAnalyze(NAMESPACE, "https://github.com/test-owner/test-repo");

        assertThat(response.promote()).isFalse();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            WireMockExtension.server.verify(
                    postRequestedFor(urlPathEqualTo("/")));
            verify(activityEvents).remediationFailed("timeout: Sandbox run exceeded the time limit");
            verify(activityEvents, never()).remediationCompleted(any());
        });
    }

    // ── K8s mock setup ─────────────────────────────────────────────────

    private void stubK8s(PodDataResult diagnostics, PodDataResult metrics) {
        // the REST endpoint defaults absent selectors to role=stable / role=canary
        when(k8sTools.getCanaryDiagnostics(NAMESPACE, null, 200, "role=stable", "role=canary")).thenReturn(diagnostics);
        when(k8sTools.getCanaryMetrics(NAMESPACE, "role=stable", "role=canary")).thenReturn(metrics);
        when(k8sTools.getRolloutStatus(NAMESPACE, null, "role=stable", "role=canary")).thenReturn(new RolloutStatusData(
                null, "demo-rollout", NAMESPACE, "Progressing", "progressing", 2, 30, 70, 2, 3));
    }

    // ── WireMock stubs ─────────────────────────────────────────────────

    private void stubLlm(String analysisJson) {
        WireMockExtension.server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("K8s SRE analysis"))
                .willReturn(okJson(chatCompletion(analysisJson))));
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
    /**
     * Stubs the remediation agent over A2A: the agent card and a SendMessage
     * call that completes immediately with the given RemediationResponse JSON
     * as the task artifact.
     */
    private void stubRemediationAgent(String responseJson) {
        var wm = WireMockExtension.server;
        String baseUrl = wm.baseUrl();

        wm.stubFor(get(urlPathEqualTo("/.well-known/agent-card.json"))
                .willReturn(okJson("""
                        {"name":"Remediation Agent",\
                        "description":"Remediates failed canary rollouts",\
                        "version":"1.0.0",\
                        "protocolVersion":"1.0.0",\
                        "capabilities":{"streaming":false,"pushNotifications":false},\
                        "defaultInputModes":["text"],\
                        "defaultOutputModes":["text"],\
                        "skills":[{"id":"remediate","name":"Remediate canary rollback"}],\
                        "supportedInterfaces":[{"protocolBinding":"JSONRPC","url":"%s"}]}"""
                        .formatted(baseUrl))));

        wm.stubFor(post(urlPathEqualTo("/"))
                .willReturn(okJson("""
                        {"jsonrpc":"2.0","id":"test-id","result":{"task":{\
                        "id":"task-1","contextId":"ctx-1",\
                        "status":{"state":"TASK_STATE_COMPLETED",\
                        "timestamp":"2026-01-01T00:00:00Z"},\
                        "artifacts":[{"artifactId":"artifact-1","parts":[{"text":"%s"}]}],\
                        "history":[]}}}"""
                        .formatted(responseJson.replace("\\", "\\\\").replace("\"", "\\\"")))));
    }

    // ── HTTP request helper ────────────────────────────────────────────

    private KubernetesAgentResponse postAnalyze(String namespace, String repoUrl) {
        return postAnalyze(namespace, repoUrl, null);
    }

    private KubernetesAgentResponse postAnalyzeWithPauseGate(String namespace, int threshold) {
        return postAnalyze(namespace, null, Map.of("pauseOnUncertainty", true, "uncertaintyThreshold", threshold));
    }

    private KubernetesAgentResponse postAnalyze(String namespace, String repoUrl, Map<String, Object> extraContext) {
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", namespace);
        if (repoUrl != null) {
            context.put("repoUrl", repoUrl);
            context.put("baseBranch", "main");
        }
        if (extraContext != null) {
            context.putAll(extraContext);
        }

        return given()
                .config(REST_CONFIG)
                .contentType("application/json")
                .body(Map.of(
                        "userId", "test-user",
                        "prompt", "Analyze canary deployment health",
                        "context", context))
                .when()
                .post("/analyze")
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



    // ── K8s diagnostics fixtures ───────────────────────────────────────

    private static PodDataResult healthyDiagnostics() {
        return PodDataResult.of(
                podData("app-stable-xyz", "Running", "1/1",
                        "INFO Application started successfully\n"
                                + "INFO Processing requests normally\n"
                                + "INFO Request completed in 45ms"),
                podData("app-canary-abc", "Running", "1/1",
                        "INFO Application started successfully\n"
                                + "INFO Processing requests normally\n"
                                + "INFO Request completed in 48ms"));
    }

    private static PodDataResult npeDiagnostics() {
        return PodDataResult.of(
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

    private static PodDataResult memoryLeakDiagnostics() {
        return PodDataResult.of(
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

    private static PodDataResult healthyMetrics() {
        return PodDataResult.of(
                metricsData(10000, 99.5, 0.5, 45.0, 120.0, null, null, null),
                metricsData(2000, 99.2, 0.8, 48.0, 125.0, null, null, null));
    }

    private static PodDataResult npeMetrics() {
        return PodDataResult.of(
                metricsData(10000, 99.5, 0.5, 45.0, 120.0, null, null, null),
                metricsData(2000, 82.0, 18.0, 250.0, 800.0, null, null, null));
    }

    private static PodDataResult memoryLeakMetrics() {
        return PodDataResult.of(
                metricsData(10000, 99.5, 0.5, 45.0, 120.0, 200.0, 512.0, 15.0),
                metricsData(2000, 75.0, 25.0, 500.0, 2000.0, 480.0, 512.0, 250.0));
    }

    // ── Data builders ──────────────────────────────────────────────────

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

    private static final String LOW_CONFIDENCE_PROMOTE = """
            {"promote":true,"confidence":55,\
            "analysis":"Canary metrics mostly within threshold, but the sample is small and the error rate difference is borderline. Promoting with reservations.",\
            "rootCause":"No clear issues, insufficient signal for a confident decision",\
            "remediation":"Promote canary but monitor closely",\
            "summary":"Canary acceptable - promote with low confidence",\
            "issueCategory":"NO_ISSUES",\
            "suspectClasses":[]}""";

    private static final String CONFIDENT_PROMOTE = """
            {"promote":true,"confidence":80,\
            "analysis":"Canary metrics within threshold. Error rates comparable, latency stable.",\
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

}
