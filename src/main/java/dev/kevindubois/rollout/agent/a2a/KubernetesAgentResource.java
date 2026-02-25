package dev.kevindubois.rollout.agent.a2a;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.text.MessageFormat;
import java.util.Map;

import dev.kevindubois.rollout.agent.agents.KubernetesAgent;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import dev.kevindubois.rollout.agent.service.AgentResponseParser;
import dev.kevindubois.rollout.agent.utils.RetryHelper;
import dev.kevindubois.rollout.agent.utils.ToolCallLimiter;

/**
 * REST API controller for Kubernetes Agent.
 * Provides HTTP endpoints for health checks and canary analysis.
 */
@Path("/a2a")
public class KubernetesAgentResource {

    @Inject
    KubernetesAgent kubernetesAgent;
    
    @Inject
    AgentResponseParser responseParser;
     
    /**
     * Main analyze endpoint
     */
    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(KubernetesAgentRequest request) {
        Log.info(MessageFormat.format("Received analysis request from user: {0}", request.userId()));
        
        try {
            // Build prompt with context
            String prompt = buildPrompt(request);
            Log.debug(MessageFormat.format("Built prompt: {0}", prompt));
            
            // Get effective memory ID (uses memoryId if provided, otherwise falls back to userId)
            String memoryId = request.getEffectiveMemoryId();
            Log.debug(MessageFormat.format("Using memory ID: {0}", memoryId));
            
            // Reset tool call limiter for this new analysis session
            ToolCallLimiter.resetSession(memoryId);
            Log.info(MessageFormat.format("Reset tool call limiter for session: {0}", memoryId));
            
            // Execute analysis with retry logic for transient errors
            String analysisResult = RetryHelper.executeWithRetryOnTransientErrors(
                () -> kubernetesAgent.chat(memoryId, prompt),
                "AI agent analysis"
            );
            
            // Parse response
            KubernetesAgentResponse response = responseParser.parse(analysisResult);
            Log.info("Analysis completed successfully");
            return Response.ok(response).build();
            
        } catch (Exception e) {
            Log.error(MessageFormat.format("Error processing request from user: {0}", request.userId()), e);
            Log.error(MessageFormat.format("Request details - Prompt: {0}", request.prompt()));
            Log.error(MessageFormat.format("Request details - Context: {0}", request.context()));
            
            // Log additional details for debugging
            if (e instanceof NullPointerException) {
                Log.error("NullPointerException detected - this may be a Gemini API response issue");
                Log.error(MessageFormat.format("Stack trace: {0}", getStackTraceAsString(e)));
            }
            
            KubernetesAgentResponse errorResponse = KubernetesAgentResponse.empty()
                .withAnalysis(MessageFormat.format("Error: {0}", e.getMessage()))
                .withRootCause("Analysis failed: " + e.getClass().getSimpleName())
                .withRemediation("Unable to provide remediation due to API error. Please try again.")
                .withPromote(true) // Default to promote on error
                .withConfidence(0);
            
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                .entity(errorResponse)
                .build();
        }
    }
    
    /**
     * Convert exception stack trace to string for logging
     */
    private String getStackTraceAsString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\n  at ").append(element.toString());
        }
        return sb.toString();
    }
    
    /**
     * Build prompt from request
     */
    private String buildPrompt(KubernetesAgentRequest request) {
        Map<String, Object> context = request.context();
        
        StringBuilder prompt = new StringBuilder();
        prompt.append(request.prompt()).append("\n\n");
        
        if (context != null) {
            prompt.append("Context:\n");
            context.forEach((key, value) -> {
                if (value != null) {
                    prompt.append("- ").append(key).append(": ").append(value).append("\n");
                }
            });
        }

        prompt.append("\nCRITICAL INSTRUCTIONS:\n");
        prompt.append("1. Gather each piece of data ONCE (max 5-7 tool calls total)\n");
        prompt.append("2. Do NOT call the same tool multiple times with the same parameters\n");
        prompt.append("3. After gathering data, STOP and analyze what you have\n");
        prompt.append("4. Make a decision based on the data collected\n");
        prompt.append("\nProvide a structured response with:\n");
        prompt.append("- analysis: Detailed analysis text\n");
        prompt.append("- rootCause: Identified root cause\n");
        prompt.append("- remediation: Suggested remediation steps\n");
        prompt.append("- prLink: GitHub PR link if applicable (can be null)\n");
        prompt.append("- promote: true to promote canary, false to abort\n");
        prompt.append("- confidence: Confidence level 0-100\n");
        
        return prompt.toString();
    }
}