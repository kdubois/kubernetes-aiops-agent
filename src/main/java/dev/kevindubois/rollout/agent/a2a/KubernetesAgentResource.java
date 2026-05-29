package dev.kevindubois.rollout.agent.a2a;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.text.MessageFormat;
import java.util.Map;

import dev.kevindubois.rollout.agent.workflow.KubernetesWorkflow;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import dev.kevindubois.rollout.agent.service.ActivityEvents;
import dev.kevindubois.rollout.agent.service.RemediationOrchestrator;
import dev.kevindubois.rollout.agent.utils.RetryHelper;
import dev.kevindubois.rollout.agent.utils.ToolCallLimiter;

/**
 * REST API controller for Kubernetes Agent.
 * Provides HTTP endpoints for health checks and canary analysis.
 */
@Path("/a2a")
public class KubernetesAgentResource {

    @Inject
    KubernetesWorkflow kubernetesWorkflow;

    @Inject
    RemediationOrchestrator remediationOrchestrator;

    @Inject
    ActivityEvents activityEvents;
     
    /**
     * Main analyze endpoint
     */
    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(KubernetesAgentRequest request) {
        Log.info(MessageFormat.format("Received analysis request from user: {0}", request.userId()));
        activityEvents.requestStarted("User: " + request.userId());

        try {
            Map<String, Object> context = request.context();
            String repoUrl = context != null ? (String) context.get("repoUrl") : null;
            String baseBranch = context != null ? (String) context.get("baseBranch") : "main";
            
            Log.info(MessageFormat.format("Context - repoUrl: {0}, baseBranch: {1}", repoUrl, baseBranch));
            
            String prompt = buildPrompt(request);
            Log.debug(MessageFormat.format("Built prompt: {0}", prompt));
            
            // Get effective memory ID (uses memoryId if provided, otherwise falls back to userId)
            String memoryId = request.getEffectiveMemoryId();
            Log.debug(MessageFormat.format("Using memory ID: {0}", memoryId));
            
            // Reset tool call limiter for this new analysis session
            ToolCallLimiter.resetSession(memoryId);
            Log.info(MessageFormat.format("Reset tool call limiter for session: {0}", memoryId));
            
            // Execute multi-agent workflow with retry logic for transient errors
            AnalysisResult analysisResult = RetryHelper.executeWithRetryOnTransientErrors(
                () -> kubernetesWorkflow.execute(memoryId, prompt, repoUrl, baseBranch),
                "Multi-agent workflow analysis"
            );

            KubernetesAgentResponse response = new KubernetesAgentResponse(
                analysisResult.analysis(),
                analysisResult.rootCause(),
                analysisResult.remediation(),
                null,
                analysisResult.promote(),
                analysisResult.confidence()
            );
            
            Log.info("Multi-agent workflow completed successfully");

            remediationOrchestrator.triggerIfNeeded(analysisResult, prompt, repoUrl, baseBranch);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            Log.error(MessageFormat.format("Error processing request from user: {0}", request.userId()), e);
            Log.error(MessageFormat.format("Request details - Prompt: {0}", request.prompt()));
            Log.error(MessageFormat.format("Request details - Context: {0}", request.context()));
            activityEvents.requestFailed(e.getMessage());
            
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
        
        return prompt.toString();
    }

}