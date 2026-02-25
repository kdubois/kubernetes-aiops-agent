package dev.kevindubois.rollout.agent;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KubernetesAgent request/response handling
 * Tests pod failure scenarios and response parsing
 */
class KubernetesAgentResponseTest {
    
    @Test
    void testCreatePodFailureRequest_CrashLoopBackOff() {
        // Given
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "default");
        context.put("podName", "test-pod");
        context.put("rolloutName", "test-rollout");
        context.put("canaryVersion", "v2");
        context.put("stableVersion", "v1");
        context.put("failureReason", "CrashLoopBackOff");
        context.put("logs", "Error: Cannot connect to database at localhost:5432\\nConnection refused");
        
        Map<String, String> event = new HashMap<>();
        event.put("type", "Warning");
        event.put("reason", "CrashLoopBackOff");
        event.put("message", "Back-off restarting failed container");
        context.put("events", new Map[]{event});
        
        context.put("repoUrl", "https://github.com/test-org/test-repo");
        
        // When
        KubernetesAgentRequest request = new KubernetesAgentRequest("test-user", "Analyze this pod failure", context);
        
        // Then
        assertEquals("test-user", request.userId());
        assertEquals("Analyze this pod failure", request.prompt());
        assertNotNull(request.context());
        assertEquals("default", request.context().get("namespace"));
        assertEquals("test-pod", request.context().get("podName"));
        assertEquals("CrashLoopBackOff", request.context().get("failureReason"));
    }
    
    @Test
    void testCreatePodFailureRequest_OOMKilled() {
        // Given
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "production");
        context.put("podName", "high-memory-pod");
        context.put("failureReason", "OOMKilled");
        context.put("logs", "java.lang.OutOfMemoryError: Java heap space");
        
        // When
        KubernetesAgentRequest request = new KubernetesAgentRequest("test-user", "Analyze OOM issue", context);
        
        // Then
        assertEquals("production", request.context().get("namespace"));
        assertEquals("OOMKilled", request.context().get("failureReason"));
        assertTrue(request.context().get("logs").toString().contains("OutOfMemoryError"));
    }
    
    @Test
    void testKubernetesAgentResponse_PromoteDecision() {
        // Test promote = true
        KubernetesAgentResponse responseTrue = new KubernetesAgentResponse(
            "Analysis",
            "Root cause",
            "Remediation",
            null,
            true,
            80
        );
        assertTrue(responseTrue.promote());
        
        // Test promote = false
        KubernetesAgentResponse responseFalse = new KubernetesAgentResponse(
            "Analysis",
            "Root cause",
            "Remediation",
            null,
            false,
            50
        );
        assertFalse(responseFalse.promote());
    }
    
    @Test
    void testKubernetesAgentResponse_FullResponse() {
        // Given
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Pod is crashing due to database connection failure",
            "Database service is not accessible",
            "Update connection string to use service name",
            "https://github.com/test-org/test-repo/pull/123",
            false,
            85
        );
        
        // Then
        assertEquals("Pod is crashing due to database connection failure", response.analysis());
        assertEquals("Database service is not accessible", response.rootCause());
        assertEquals("Update connection string to use service name", response.remediation());
        assertEquals("https://github.com/test-org/test-repo/pull/123", response.prLink());
        assertFalse(response.promote());
        assertEquals(85, response.confidence());
    }
    
    @Test
    void testKubernetesAgentResponse_ErrorScenario() {
        // Given
        KubernetesAgentResponse response = new KubernetesAgentResponse(
            "Error: Analysis failed",
            "Analysis failed",
            "Unable to provide remediation",
            null,
            true,
            0
        );
        
        // Then
        assertTrue(response.analysis().contains("Error"));
        assertTrue(response.promote());
        assertEquals(0, response.confidence());
    }
    
    @Test
    void testCreatePodFailureRequest_ImagePullBackOff() {
        // Given
        Map<String, Object> context = new HashMap<>();
        context.put("namespace", "staging");
        context.put("podName", "app-pod");
        context.put("failureReason", "ImagePullBackOff");
        context.put("logs", "Failed to pull image: repository does not exist");
        
        // When
        KubernetesAgentRequest request = new KubernetesAgentRequest("test-user", "Debug image pull error", context);
        
        // Then
        assertEquals("ImagePullBackOff", request.context().get("failureReason"));
        assertTrue(request.context().get("logs").toString().contains("repository does not exist"));
    }
    
    @Test
    void testKubernetesAgentRequest_WithMultipleEvents() {
        // Given
        Map<String, Object> context = new HashMap<>();
        
        Map<String, String> event1 = new HashMap<>();
        event1.put("type", "Warning");
        event1.put("reason", "BackOff");
        event1.put("message", "Back-off restarting failed container");
        
        Map<String, String> event2 = new HashMap<>();
        event2.put("type", "Warning");
        event2.put("reason", "Failed");
        event2.put("message", "Error: container failed");
        
        context.put("events", new Map[]{event1, event2});
        
        // When
        KubernetesAgentRequest request = new KubernetesAgentRequest("argo-rollouts", "Analyze canary failures", context);
        
        // Then
        Object[] events = (Object[]) request.context().get("events");
        assertNotNull(events);
        assertEquals(2, events.length);
    }
    
    @Test
    void testKubernetesAgentResponse_BuilderMethods() {
        // Given
        KubernetesAgentResponse response = new KubernetesAgentResponse("Initial analysis", "Initial root cause", "Initial remediation", null, false, 50);
        
        // When
        KubernetesAgentResponse updatedResponse = response
            .withAnalysis("Updated analysis")
            .withRootCause("Updated root cause")
            .withRemediation("Updated remediation")
            .withPrLink("https://github.com/test-org/test-repo/pull/456")
            .withPromote(true)
            .withConfidence(95);
        
        // Then
        assertEquals("Updated analysis", updatedResponse.analysis());
        assertEquals("Updated root cause", updatedResponse.rootCause());
        assertEquals("Updated remediation", updatedResponse.remediation());
        assertEquals("https://github.com/test-org/test-repo/pull/456", updatedResponse.prLink());
        assertTrue(updatedResponse.promote());
        assertEquals(95, updatedResponse.confidence());
        
        // Original response should be unchanged (immutability)
        assertEquals("Initial analysis", response.analysis());
        assertEquals("Initial root cause", response.rootCause());
        assertEquals("Initial remediation", response.remediation());
        assertNull(response.prLink());
        assertFalse(response.promote());
        assertEquals(50, response.confidence());
    }
}