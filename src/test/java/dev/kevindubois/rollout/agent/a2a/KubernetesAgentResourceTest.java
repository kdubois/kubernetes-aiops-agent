package dev.kevindubois.rollout.agent.a2a;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import dev.kevindubois.rollout.agent.agents.KubernetesAgent;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import dev.kevindubois.rollout.agent.service.AgentResponseParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for KubernetesAgentResource.
 * Tests the REST API endpoint for Kubernetes analysis including prompt building,
 * error handling, and response formatting.
 */
@QuarkusTest
class KubernetesAgentResourceTest {

    @Inject
    KubernetesAgentResource resource;

    @InjectMock
    KubernetesAgent kubernetesAgent;

    @InjectMock
    AgentResponseParser responseParser;

    private KubernetesAgentRequest baseRequest;

    @BeforeEach
    void setUp() {
        // Create base request for tests
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "default");
        context.put("podName", "test-pod");
        
        baseRequest = new KubernetesAgentRequest(
            "user-123",
            "Analyze the canary deployment",
            context,
            null  // no explicit memoryId
        );
    }

    // ========== Successful Analysis Tests ==========

    @Test
    void testAnalyze_successfulAnalysis() {
        // Given: Valid request and successful agent response
        String agentResponse = """
            ## Analysis
            Canary deployment is healthy
            
            ## Root Cause
            No issues detected
            
            ## Decision
            promote: true
            """;
        
        KubernetesAgentResponse parsedResponse = new KubernetesAgentResponse(
            agentResponse, "No issues detected", "Continue deployment", null, true, 90
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn(agentResponse);
        when(responseParser.parse(agentResponse)).thenReturn(parsedResponse);

        // When: Analyze is called
        Response response = resource.analyze(baseRequest);

        // Then: Successful response is returned
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isInstanceOf(KubernetesAgentResponse.class);
        
        KubernetesAgentResponse result = (KubernetesAgentResponse) response.getEntity();
        assertThat(result.promote()).isTrue();
        assertThat(result.confidence()).isEqualTo(90);
        
        verify(kubernetesAgent).chat(anyString(), anyString());
        verify(responseParser).parse(agentResponse);
    }

    @Test
    void testAnalyze_withMemoryId() {
        // Given: Request with explicit memory ID
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "default");
        
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Analyze deployment",
            context,
            "explicit-memory-456"
        );
        
        String agentResponse = "Analysis result";
        KubernetesAgentResponse parsedResponse = new KubernetesAgentResponse(
            agentResponse, "Root", "Remediation", null, true, 80
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn(agentResponse);
        when(responseParser.parse(agentResponse)).thenReturn(parsedResponse);

        // When: Analyze is called
        Response response = resource.analyze(request);

        // Then: Memory ID is used
        ArgumentCaptor<String> memoryIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(kubernetesAgent).chat(memoryIdCaptor.capture(), anyString());
        assertThat(memoryIdCaptor.getValue()).isEqualTo("explicit-memory-456");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ========== Prompt Building Tests ==========

    @Test
    void testAnalyze_buildPrompt() {
        // Given: Request with context
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "production");
        context.put("podName", "canary-pod-123");
        context.put("errorRate", "5%");
        
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Check canary health",
            context,
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        resource.analyze(request);

        // Then: Prompt includes all context
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(kubernetesAgent).chat(anyString(), promptCaptor.capture());
        
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("Check canary health");
        assertThat(prompt).contains("Context:");
        assertThat(prompt).contains("namespace: production");
        assertThat(prompt).contains("podName: canary-pod-123");
        assertThat(prompt).contains("errorRate: 5%");
        assertThat(prompt).contains("CRITICAL INSTRUCTIONS:");
        assertThat(prompt).contains("max 5-7 tool calls total");
    }

    @Test
    void testAnalyze_promptWithNullContext() {
        // Given: Request with null context
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Analyze deployment",
            null,  // null context
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        resource.analyze(request);

        // Then: Prompt is built without context section
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(kubernetesAgent).chat(anyString(), promptCaptor.capture());
        
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("Analyze deployment");
        assertThat(prompt).contains("CRITICAL INSTRUCTIONS:");
        // Context section should not be present
        assertThat(prompt).doesNotContain("Context:\n-");
    }

    @Test
    void testAnalyze_promptWithEmptyContext() {
        // Given: Request with empty context
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Analyze deployment",
            new HashMap<>(),  // empty context
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        resource.analyze(request);

        // Then: Prompt is built with empty context
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(kubernetesAgent).chat(anyString(), promptCaptor.capture());
        
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("Analyze deployment");
        assertThat(prompt).contains("CRITICAL INSTRUCTIONS:");
    }

    @Test
    void testAnalyze_promptWithNullContextValues() {
        // Given: Request with null values in context
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "default");
        context.put("podName", null);  // null value
        context.put("errorRate", "5%");
        
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Check deployment",
            context,
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        resource.analyze(request);

        // Then: Null values are skipped in prompt
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(kubernetesAgent).chat(anyString(), promptCaptor.capture());
        
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("namespace: default");
        assertThat(prompt).contains("errorRate: 5%");
        assertThat(prompt).doesNotContain("podName: null");
    }

    // ========== Error Handling Tests ==========

    @Test
    void testAnalyze_errorResponse() {
        // Given: Agent throws exception
        when(kubernetesAgent.chat(anyString(), anyString()))
            .thenThrow(new RuntimeException("Agent execution failed"));

        // When: Analyze is called
        Response response = resource.analyze(baseRequest);

        // Then: Error response is returned
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getEntity()).isInstanceOf(KubernetesAgentResponse.class);
        
        KubernetesAgentResponse errorResponse = (KubernetesAgentResponse) response.getEntity();
        assertThat(errorResponse.analysis()).contains("Error:");
        assertThat(errorResponse.rootCause()).contains("Analysis failed");
        assertThat(errorResponse.remediation()).contains("Unable to provide remediation");
        assertThat(errorResponse.promote()).isTrue();  // Default to promote on error
        assertThat(errorResponse.confidence()).isEqualTo(0);
    }

    @Test
    void testAnalyze_stackTraceLogging() {
        // Given: NullPointerException is thrown
        when(kubernetesAgent.chat(anyString(), anyString()))
            .thenThrow(new NullPointerException("Null value encountered"));

        // When: Analyze is called
        Response response = resource.analyze(baseRequest);

        // Then: Error is handled and logged (stack trace logged internally)
        assertThat(response.getStatus()).isEqualTo(500);
        
        KubernetesAgentResponse errorResponse = (KubernetesAgentResponse) response.getEntity();
        assertThat(errorResponse.rootCause()).contains("NullPointerException");
    }

    @Test
    void testAnalyze_parserError() {
        // Given: Parser throws exception
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("valid response");
        when(responseParser.parse(anyString()))
            .thenThrow(new RuntimeException("Parser error"));

        // When: Analyze is called
        Response response = resource.analyze(baseRequest);

        // Then: Error response is returned
        assertThat(response.getStatus()).isEqualTo(500);
        
        KubernetesAgentResponse errorResponse = (KubernetesAgentResponse) response.getEntity();
        assertThat(errorResponse.analysis()).contains("Error:");
        assertThat(errorResponse.rootCause()).contains("Analysis failed");
    }

    @Test
    void testAnalyze_agentExecutionError() {
        // Given: Agent execution fails
        when(kubernetesAgent.chat(anyString(), anyString()))
            .thenThrow(new IllegalStateException("Invalid state"));

        // When: Analyze is called
        Response response = resource.analyze(baseRequest);

        // Then: Error is handled gracefully
        assertThat(response.getStatus()).isEqualTo(500);
        
        KubernetesAgentResponse errorResponse = (KubernetesAgentResponse) response.getEntity();
        assertThat(errorResponse.rootCause()).contains("IllegalStateException");
        assertThat(errorResponse.promote()).isTrue();  // Safe default
    }

    // ========== Response Formatting Tests ==========

    @Test
    void testAnalyze_responseFormatting() {
        // Given: Successful analysis
        String agentResponse = """
            ## Analysis
            Detailed analysis text
            
            ## Root Cause
            Configuration error
            
            ## Remediation
            Fix the config
            
            ## Decision
            promote: false""";
        
        KubernetesAgentResponse parsedResponse = new KubernetesAgentResponse(
            agentResponse,
            "Configuration error",
            "Fix the config",
            "https://github.com/org/repo/pull/123",
            false,
            60
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn(agentResponse);
        when(responseParser.parse(agentResponse)).thenReturn(parsedResponse);

        // When: Analyze is called
        Response response = resource.analyze(baseRequest);

        // Then: Response is properly formatted
        assertThat(response.getStatus()).isEqualTo(200);
        
        KubernetesAgentResponse result = (KubernetesAgentResponse) response.getEntity();
        assertThat(result.analysis()).isEqualTo(agentResponse);
        assertThat(result.rootCause()).isEqualTo("Configuration error");
        assertThat(result.remediation()).isEqualTo("Fix the config");
        assertThat(result.prLink()).isEqualTo("https://github.com/org/repo/pull/123");
        assertThat(result.promote()).isFalse();
        assertThat(result.confidence()).isEqualTo(60);
    }

    // ========== Validation Tests ==========

    @Test
    void testAnalyze_nullPodName() {
        // Given: Request with null pod name in context
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "default");
        context.put("podName", null);
        
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Analyze deployment",
            context,
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        Response response = resource.analyze(request);

        // Then: Request is processed (null values are handled)
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void testAnalyze_emptyNamespace() {
        // Given: Request with empty namespace
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "");
        context.put("podName", "test-pod");
        
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            "Analyze deployment",
            context,
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        Response response = resource.analyze(request);

        // Then: Request is processed
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void testAnalyze_invalidRequest() {
        // Given: Request with null prompt
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-123",
            null,  // null prompt
            new HashMap<>(),
            null
        );
        
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Analyze is called
        Response response = resource.analyze(request);

        // Then: Request is processed (null prompt handled)
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ========== Integration Tests ==========

    @Test
    void testAnalyze_fullWorkflow() {
        // Given: Complete request with all fields
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "production");
        context.put("podName", "canary-pod-abc");
        context.put("errorRate", "2%");
        context.put("latency", "150ms");
        
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "user-456",
            "Analyze canary deployment health",
            context,
            "memory-789"
        );
        
        String agentResponse = """
            ## Analysis
            Canary shows acceptable performance
            
            ## Root Cause
            Slight latency increase within tolerance
            
            ## Remediation
            Monitor for 5 more minutes
            
            ## Decision
            promote: true
            """;
        
        KubernetesAgentResponse parsedResponse = new KubernetesAgentResponse(
            agentResponse,
            "Slight latency increase within tolerance",
            "Monitor for 5 more minutes",
            null,
            true,
            85
        );
        
        when(kubernetesAgent.chat(eq("memory-789"), anyString())).thenReturn(agentResponse);
        when(responseParser.parse(agentResponse)).thenReturn(parsedResponse);

        // When: Analyze is called
        Response response = resource.analyze(request);

        // Then: Full workflow completes successfully
        assertThat(response.getStatus()).isEqualTo(200);
        
        KubernetesAgentResponse result = (KubernetesAgentResponse) response.getEntity();
        assertThat(result.promote()).isTrue();
        assertThat(result.confidence()).isEqualTo(85);
        assertThat(result.rootCause()).contains("latency");
        
        // Verify memory ID was used
        verify(kubernetesAgent).chat(eq("memory-789"), anyString());
    }

    @Test
    void testAnalyze_multipleRequests() {
        // Given: Multiple sequential requests
        when(kubernetesAgent.chat(anyString(), anyString())).thenReturn("response");
        when(responseParser.parse(anyString())).thenReturn(
            new KubernetesAgentResponse("analysis", "root", "remediation", null, true, 80)
        );

        // When: Multiple analyze calls are made
        Response response1 = resource.analyze(baseRequest);
        Response response2 = resource.analyze(baseRequest);
        Response response3 = resource.analyze(baseRequest);

        // Then: All requests are processed successfully
        assertThat(response1.getStatus()).isEqualTo(200);
        assertThat(response2.getStatus()).isEqualTo(200);
        assertThat(response3.getStatus()).isEqualTo(200);
        
        verify(kubernetesAgent, times(3)).chat(anyString(), anyString());
    }
}
