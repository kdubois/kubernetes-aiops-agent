package org.csanchez.adk.agents.k8sagent.a2a;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for A2AController that test actual controller methods
 * 
 * These tests require GOOGLE_API_KEY to be set in the environment.
 * They will be skipped if the API key is not available.
 */
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".*")
class A2AControllerIntegrationTest {
	
	private A2AController controller;
	private InMemoryRunner runner;
	
	@BeforeEach
	void setUp() {
		// Create a minimal agent for testing
		// IMPORTANT: Agent name must match what the controller uses ("KubernetesAgent")
		BaseAgent testAgent = LlmAgent.builder()
			.model("gemini-2.0-flash-exp")
			.name("KubernetesAgent")
			.description("Test agent for integration tests")
			.instruction("You are a Kubernetes debugging agent. Analyze pod failures and provide brief recommendations.")
			.build();
		
		runner = new InMemoryRunner(testAgent);
		
		// Use constructor injection - no reflection needed!
		controller = new A2AController(runner);
	}
	
	@Test
	void testHealthEndpoint() {
		// When
		ResponseEntity<Map<String, Object>> response = controller.health();
		
		// Then
		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertNotNull(response.getBody());
		assertEquals("healthy", response.getBody().get("status"));
		assertEquals("KubernetesAgent", response.getBody().get("agent"));
		assertEquals("1.0.0", response.getBody().get("version"));
	}
	
	@Test
	void testAnalyzePodFailure_CrashLoopBackOff() {
		// Given - Create a realistic pod failure scenario
		A2ARequest request = createPodFailureRequest(
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
		ResponseEntity<A2AResponse> response = controller.analyze(request);
		
		// Then - Verify the response
		assertNotNull(response, "Response should not be null");
		assertEquals(HttpStatus.OK, response.getStatusCode(), 
			"Response should be OK even if analysis has issues");
		
		A2AResponse body = response.getBody();
		assertNotNull(body, "Response body should not be null");
		assertNotNull(body.getAnalysis(), "Analysis should be provided");
		assertNotNull(body.getRootCause(), "Root cause should be identified");
		assertNotNull(body.getRemediation(), "Remediation should be suggested");
		
		// The response should contain relevant information about the failure
		String analysis = body.getAnalysis().toLowerCase();
		assertTrue(analysis.contains("database") || analysis.contains("connection") || 
			analysis.contains("localhost") || analysis.contains("5432"),
			"Analysis should mention the database connection issue");
		
		System.out.println("=== Test: CrashLoopBackOff ===");
		System.out.println("Analysis: " + body.getAnalysis());
		System.out.println("Root Cause: " + body.getRootCause());
		System.out.println("Remediation: " + body.getRemediation());
		System.out.println("Promote: " + body.isPromote());
		System.out.println("Confidence: " + body.getConfidence());
	}
	
	@Test
	void testAnalyzePodFailure_OOMKilled() {
		// Given
		A2ARequest request = createPodFailureRequest(
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
		ResponseEntity<A2AResponse> response = controller.analyze(request);
		
		// Then
		assertNotNull(response);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		
		A2AResponse body = response.getBody();
		assertNotNull(body);
		assertNotNull(body.getAnalysis());
		
		String analysis = body.getAnalysis().toLowerCase();
		assertTrue(analysis.contains("memory") || analysis.contains("oom") || 
			analysis.contains("heap"),
			"Analysis should mention memory/OOM issues");
		
		System.out.println("\n=== Test: OOMKilled ===");
		System.out.println("Analysis: " + body.getAnalysis());
		System.out.println("Promote: " + body.isPromote());
		System.out.println("Confidence: " + body.getConfidence());
	}
	
	@Test
	void testAnalyzePodFailure_ImagePullBackOff() {
		// Given
		A2ARequest request = createPodFailureRequest(
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
		ResponseEntity<A2AResponse> response = controller.analyze(request);
		
		// Then
		assertNotNull(response);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		
		A2AResponse body = response.getBody();
		assertNotNull(body);
		assertNotNull(body.getAnalysis());
		
		String analysis = body.getAnalysis().toLowerCase();
		assertTrue(analysis.contains("image") || analysis.contains("pull") || 
			analysis.contains("manifest") || analysis.contains("registry"),
			"Analysis should mention image pull issues");
		
		System.out.println("\n=== Test: ImagePullBackOff ===");
		System.out.println("Analysis: " + body.getAnalysis());
		System.out.println("Promote: " + body.isPromote());
	}
	
	@Test
	void testBuildPrompt() {
		// Test that the controller properly builds prompts with context
		A2ARequest request = createPodFailureRequest(
			"test-user",
			"Test prompt",
			"test-ns",
			"test-pod",
			"TestFailure",
			"Test logs"
		);
		
		// We can't directly test buildPrompt as it's private, but we can test
		// that analyze works with the request, which exercises buildPrompt internally
		ResponseEntity<A2AResponse> response = controller.analyze(request);
		
		assertNotNull(response);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertNotNull(response.getBody());
	}
	
	@Test
	void testAnalyzeWithMinimalContext() {
		// Test with minimal context to ensure the controller handles it gracefully
		A2ARequest request = new A2ARequest();
		request.setUserId("test-user");
		request.setPrompt("Quick analysis");
		
		Map<String, Object> context = new HashMap<>();
		context.put("namespace", "default");
		context.put("podName", "minimal-pod");
		request.setContext(context);
		
		ResponseEntity<A2AResponse> response = controller.analyze(request);
		
		assertNotNull(response);
		// Should still work even with minimal context
		assertNotNull(response.getBody());
	}
	
	/**
	 * Helper method to create a realistic pod failure request
	 */
	private A2ARequest createPodFailureRequest(
		String userId,
		String prompt,
		String namespace,
		String podName,
		String failureReason,
		String logs
	) {
		A2ARequest request = new A2ARequest();
		request.setUserId(userId);
		request.setPrompt(prompt);
		
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
		
		request.setContext(context);
		
		return request;
	}
}

