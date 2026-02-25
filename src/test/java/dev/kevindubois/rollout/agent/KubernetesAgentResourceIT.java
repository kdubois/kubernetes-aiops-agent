package dev.kevindubois.rollout.agent;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import dev.kevindubois.rollout.agent.a2a.KubernetesAgentResource;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for KubernetesAgentResource that test actual controller methods
 *
 * These tests require OPENAI_API_KEY to be set in the environment.
 * They will be skipped if the API key is not available.
 */
@QuarkusTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".*")
class KubernetesAgentResourceIT {
    
    @Inject
    KubernetesAgentResource controller;
    
    @Test
    void testHealthEndpoint() {
        // When - Call the Quarkus health endpoint
        given()
            .when()
            .get("/q/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("checks", notNullValue());
    }
    
    @Test
    void testAnalyzePodFailure_CrashLoopBackOff() {
        // Given - Create a realistic pod failure scenario
        KubernetesAgentRequest request = createPodFailureRequest(
            "test-user",
            "Analyze this pod failure and suggest if we should promote the canary",
            "default",
            "test-pod",
            "CrashLoopBackOff",
            "Error: Cannot connect to database at localhost:5432\n" +
            "Connection refused\n" +
            "Exiting with status 1"
        );
        
        // When - Call the actual controller method
        Response response = controller.analyze(request);
        
        // Then - Verify the response
        assertNotNull(response, "Response should not be null");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(), 
            "Response should be OK even if analysis has issues");
        
        KubernetesAgentResponse body = (KubernetesAgentResponse) response.getEntity();
        assertNotNull(body, "Response body should not be null");
        assertNotNull(body.analysis(), "Analysis should be provided");
        assertNotNull(body.rootCause(), "Root cause should be identified");
        assertNotNull(body.remediation(), "Remediation should be suggested");
        
        // The response should contain relevant information about the failure
        String analysis = body.analysis().toLowerCase();
        assertTrue(analysis.contains("database") || analysis.contains("connection") || 
            analysis.contains("pod"),
            "Analysis should mention the database connection issue");
        
        System.out.println("=== Test: CrashLoopBackOff ===");
        System.out.println("Analysis: " + body.analysis());
        System.out.println("Root Cause: " + body.rootCause());
        System.out.println("Remediation: " + body.remediation());
        System.out.println("Promote: " + body.promote());
        System.out.println("Confidence: " + body.confidence());
    }
    
    @Test
    void testAnalyzePodFailure_OOMKilled() {
        // Given
        KubernetesAgentRequest request = createPodFailureRequest(
            "test-user",
            "The pod was OOMKilled. Should we promote this canary?",
            "production",
            "high-memory-pod",
            "OOMKilled",
            "java.lang.OutOfMemoryError: Java heap space\n" +
            "	at java.util.Arrays.copyOf(Arrays.java:3332)\n" +
            "	at java.lang.AbstractStringBuilder.ensureCapacityInternal"
        );
        
        // When
        Response response = controller.analyze(request);
        
        // Then
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        
        KubernetesAgentResponse body = (KubernetesAgentResponse) response.getEntity();
        assertNotNull(body);
        assertNotNull(body.analysis());
        
        System.out.println("\n=== Test: OOMKilled ===");
        System.out.println("Analysis: " + body.analysis());
        System.out.println("Promote: " + body.promote());
        System.out.println("Confidence: " + body.confidence());
    }
    
    @Test
    void testAnalyzePodFailure_ImagePullBackOff() {
        // Given
        KubernetesAgentRequest request = createPodFailureRequest(
            "test-user",
            "Pod cannot pull image. Analyze and recommend action.",
            "staging",
            "app-pod",
            "ImagePullBackOff",
            "Failed to pull image \"myregistry.io/app:v2.0.0\": " +
            "rpc error: code = Unknown desc = Error response from daemon: " +
            "manifest for myregistry.io/app:v2.0.0 not found"
        );
        
        // When
        Response response = controller.analyze(request);
        
        // Then
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        
        KubernetesAgentResponse body = (KubernetesAgentResponse) response.getEntity();
        assertNotNull(body);
        assertNotNull(body.analysis());
        
        System.out.println("\n=== Test: ImagePullBackOff ===");
        System.out.println("Analysis: " + body.analysis());
        System.out.println("Promote: " + body.promote());
    }
    
    @Test
    void testBuildPrompt() {
        // Test that the controller properly builds prompts with context
        KubernetesAgentRequest request = createPodFailureRequest(
            "test-user",
            "Test prompt",
            "test-ns",
            "test-pod",
            "TestFailure",
            "Test logs"
        );
        
        // We can test that analyze works with the request
        Response response = controller.analyze(request);
        
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertNotNull(response.getEntity());
    }
    
    @Test
    void testAnalyzeWithMinimalContext() {
        // Test with minimal context to ensure the controller handles it gracefully
        KubernetesAgentRequest request = new KubernetesAgentRequest(
            "test-user",
            "Quick analysis",
            Map.of("namespace", "default", "podName", "minimal-pod")
        );
        
        Response response = controller.analyze(request);
        
        assertNotNull(response);
        // Should still work even with minimal context
        assertNotNull(response.getEntity());
    }
    
    /**
     * Helper method to create a realistic pod failure request
     */
    private KubernetesAgentRequest createPodFailureRequest(
        String userId,
        String prompt,
        String namespace,
        String podName,
        String failureReason,
        String logs
    ) {
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", namespace);
        context.put("podName", podName);
        context.put("rolloutName", "test-rollout");
        context.put("canaryVersion", "v2");
        context.put("stableVersion", "v1");
        context.put("failureReason", failureReason);
        context.put("logs", logs);
        
        Map<String, String> event = new HashMap<>();
        event.put("type", "Warning");
        event.put("reason", failureReason);
        event.put("message", "Pod is failing: " + failureReason);
        context.put("events", new Map[]{event});
        
        context.put("repoUrl", "https://github.com/test-org/test-repo");
        
        return new KubernetesAgentRequest(userId, prompt, context);
    }
}

