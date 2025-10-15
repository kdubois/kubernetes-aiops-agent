package org.csanchez.adk.agents.k8sagent.a2a;

import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import org.csanchez.adk.agents.k8sagent.KubernetesAgent;
import org.csanchez.adk.agents.k8sagent.utils.RetryHelper;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Agent-to-Agent (A2A) communication
 */
@RestController
@RequestMapping("/a2a")
public class A2AController {
	
	private static final Logger logger = LoggerFactory.getLogger(A2AController.class);
	
	private final InMemoryRunner runner;
	
	/**
	 * Constructor with dependency injection for testability
	 */
	@Autowired
	public A2AController(InMemoryRunner runner) {
		this.runner = runner;
	}
	
	/**
	 * Health check endpoint
	 */
	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		return ResponseEntity.ok(Map.of(
			"status", "healthy",
			"agent", "KubernetesAgent",
			"version", "1.0.0"
		));
	}
	
	/**
	 * Main A2A analyze endpoint
	 * Called by rollouts-plugin-metric-ai to analyze canary issues
	 */
	@PostMapping("/analyze")
	public ResponseEntity<A2AResponse> analyze(@RequestBody A2ARequest request) {
		logger.info("Received A2A analysis request from user: {}", request.getUserId());
		
		try {
			// Create session for this analysis
			String sessionId = "a2a-" + System.currentTimeMillis();
			Session session = runner.sessionService()
				.createSession("KubernetesAgent", request.getUserId())
				.blockingGet();
			
			// Build prompt with context
			String prompt = buildPrompt(request);
			logger.debug("Built prompt: {}", prompt);
			
			// Invoke agent with retry logic for 429 errors
			Content userMsg = Content.fromParts(Part.fromText(prompt));
			List<String> responses = RetryHelper.executeWithRetry(() -> {
				Flowable<Event> events = runner.runAsync(
					request.getUserId(),
					session.id(),
					userMsg
				);
				
				// Collect results
				List<String> eventResponses = new ArrayList<>();
				events.blockingForEach(event -> {
					String content = event.stringifyContent();
					if (content != null && !content.isEmpty()) {
						eventResponses.add(content);
						logger.debug("Received event content: {}", content);
					}
				});
				
				return eventResponses;
			}, "Gemini API analysis");
			
			// Parse response
			String fullResponse = String.join("\n", responses);
			A2AResponse response = parseResponse(fullResponse, request.getContext());
			
			logger.info("A2A analysis completed successfully: {}", fullResponse);
			return ResponseEntity.ok(response);
			
		} catch (Exception e) {
			logger.error("Error processing A2A request from user: {}", request.getUserId(), e);
			logger.error("Request details - Prompt: {}", request.getPrompt());
			logger.error("Request details - Context: {}", request.getContext());
			
			A2AResponse errorResponse = new A2AResponse();
			errorResponse.setAnalysis("Error: " + e.getMessage());
			errorResponse.setRootCause("Analysis failed");
			errorResponse.setRemediation("Unable to provide remediation");
			errorResponse.setPromote(true); // Default to promote on error
			errorResponse.setConfidence(0);
			
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(errorResponse);
		}
	}
	
	/**
	 * Build prompt from A2A request
	 */
	private String buildPrompt(A2ARequest request) {
		Map<String, Object> context = request.getContext();
		
		StringBuilder prompt = new StringBuilder();
		prompt.append(request.getPrompt()).append("\n\n");
		
		if (context != null) {
			prompt.append("Context:\n");
			context.forEach((key, value) -> {
				if (value != null) {
					prompt.append("- ").append(key).append(": ").append(value).append("\n");
				}
			});
		}
		
		prompt.append("\nPlease analyze the canary deployment and provide:\n");
		prompt.append("1. Root cause of any issues\n");
		prompt.append("2. Whether to promote the canary (true/false)\n");
		prompt.append("3. Confidence level (0-100)\n");
		prompt.append("4. Remediation steps if needed\n");
		
		return prompt.toString();
	}
	
	/**
	 * Parse agent response into A2AResponse
	 */
	private A2AResponse parseResponse(String fullResponse, Map<String, Object> context) {
		A2AResponse response = new A2AResponse();
		
		// Simple parsing - in production, this would be more sophisticated
		response.setAnalysis(fullResponse);
		
		// Try to extract structured data
		boolean promote = !fullResponse.toLowerCase().contains("do not promote") &&
			!fullResponse.toLowerCase().contains("abort") &&
			!fullResponse.toLowerCase().contains("rollback");
		
		response.setPromote(promote);
		
		// Extract root cause (look for common patterns)
		String rootCause = extractSection(fullResponse, "root cause");
		response.setRootCause(rootCause != null ? rootCause : "See analysis");
		
		// Extract remediation
		String remediation = extractSection(fullResponse, "remediation");
		response.setRemediation(remediation != null ? remediation : "See analysis");
		
		// Set confidence (would be extracted from agent response in production)
		response.setConfidence(promote ? 80 : 50);
		
		return response;
	}
	
	/**
	 * Extract a section from the response by looking for headers
	 */
	private String extractSection(String text, String sectionName) {
		String lowerText = text.toLowerCase();
		String lowerSection = sectionName.toLowerCase();
		
		int start = lowerText.indexOf(lowerSection);
		if (start == -1) {
			return null;
		}
		
		// Find the end (next section or end of text)
		int end = text.length();
		for (String marker : List.of("\n## ", "\n# ", "\n\n## ")) {
			int markerPos = text.indexOf(marker, start + sectionName.length());
			if (markerPos != -1 && markerPos < end) {
				end = markerPos;
			}
		}
		
		return text.substring(start, end).trim();
	}
}


