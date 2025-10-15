package org.csanchez.adk.agents.k8sagent.a2a;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for A2A request/response handling
 * Tests pod failure scenarios and response parsing
 */
class A2AResponseTest {
	
	@Test
	void testHealthEndpointResponse() {
		// Health endpoint doesn't use the runner, so we can pass null
		A2AController controller = new A2AController(null);
		
		var response = controller.health();
		
		assertNotNull(response);
		assertEquals(200, response.getStatusCodeValue());
		assertNotNull(response.getBody());
		assertEquals("healthy", response.getBody().get("status"));
		assertEquals("KubernetesAgent", response.getBody().get("agent"));
	}
	
	@Test
	void testCreatePodFailureRequest_CrashLoopBackOff() {
		// Given
		A2ARequest request = new A2ARequest();
		request.setUserId("test-user");
		request.setPrompt("Analyze this pod failure");
		
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
		request.setContext(context);
		
		// Then
		assertEquals("test-user", request.getUserId());
		assertEquals("Analyze this pod failure", request.getPrompt());
		assertNotNull(request.getContext());
		assertEquals("default", request.getContext().get("namespace"));
		assertEquals("test-pod", request.getContext().get("podName"));
		assertEquals("CrashLoopBackOff", request.getContext().get("failureReason"));
	}
	
	@Test
	void testCreatePodFailureRequest_OOMKilled() {
		// Given
		A2ARequest request = new A2ARequest();
		request.setUserId("test-user");
		request.setPrompt("Analyze OOM issue");
		
		Map<String, Object> context = new HashMap<>();
		context.put("namespace", "production");
		context.put("podName", "high-memory-pod");
		context.put("failureReason", "OOMKilled");
		context.put("logs", "java.lang.OutOfMemoryError: Java heap space");
		request.setContext(context);
		
		// Then
		assertEquals("production", request.getContext().get("namespace"));
		assertEquals("OOMKilled", request.getContext().get("failureReason"));
		assertTrue(request.getContext().get("logs").toString().contains("OutOfMemoryError"));
	}
	
	@Test
	void testA2AResponse_PromoteDecision() {
		A2AResponse response = new A2AResponse();
		
		// Test promote = true
		response.setPromote(true);
		assertTrue(response.isPromote());
		
		// Test promote = false
		response.setPromote(false);
		assertFalse(response.isPromote());
	}
	
	@Test
	void testA2AResponse_FullResponse() {
		A2AResponse response = new A2AResponse();
		response.setAnalysis("Pod is crashing due to database connection failure");
		response.setRootCause("Database service is not accessible");
		response.setRemediation("Update connection string to use service name");
		response.setPrLink("https://github.com/test-org/test-repo/pull/123");
		response.setPromote(false);
		response.setConfidence(85);
		
		assertEquals("Pod is crashing due to database connection failure", response.getAnalysis());
		assertEquals("Database service is not accessible", response.getRootCause());
		assertEquals("Update connection string to use service name", response.getRemediation());
		assertEquals("https://github.com/test-org/test-repo/pull/123", response.getPrLink());
		assertFalse(response.isPromote());
		assertEquals(85, response.getConfidence());
	}
	
	@Test
	void testA2AResponse_ErrorScenario() {
		A2AResponse response = new A2AResponse();
		response.setAnalysis("Error: Analysis failed");
		response.setRootCause("Analysis failed");
		response.setRemediation("Unable to provide remediation");
		response.setPromote(true); // Promote on error
		response.setConfidence(0);
		
		assertTrue(response.getAnalysis().contains("Error"));
		assertTrue(response.isPromote());
		assertEquals(0, response.getConfidence());
	}
	
	@Test
	void testCreatePodFailureRequest_ImagePullBackOff() {
		A2ARequest request = new A2ARequest();
		request.setUserId("test-user");
		request.setPrompt("Debug image pull error");
		
		Map<String, Object> context = new HashMap<>();
		context.put("namespace", "staging");
		context.put("podName", "app-pod");
		context.put("failureReason", "ImagePullBackOff");
		context.put("logs", "Failed to pull image: repository does not exist");
		request.setContext(context);
		
		assertEquals("ImagePullBackOff", request.getContext().get("failureReason"));
		assertTrue(request.getContext().get("logs").toString().contains("repository does not exist"));
	}
	
	@Test
	void testA2ARequest_WithMultipleEvents() {
		A2ARequest request = new A2ARequest();
		request.setUserId("argo-rollouts");
		request.setPrompt("Analyze canary failures");
		
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
		request.setContext(context);
		
		Object[] events = (Object[]) request.getContext().get("events");
		assertNotNull(events);
		assertEquals(2, events.length);
	}
}
