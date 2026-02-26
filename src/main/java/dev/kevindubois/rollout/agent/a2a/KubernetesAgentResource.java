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
            
            // Execute multi-agent workflow with retry logic for transient errors
            AnalysisResult analysisResult = RetryHelper.executeWithRetryOnTransientErrors(
                () -> kubernetesWorkflow.execute(memoryId, prompt),
                "Multi-agent workflow analysis"
            );
            
            // Validate PR link is not hallucinated
            if (analysisResult.prLink() != null && isHallucinatedUrl(analysisResult.prLink())) {
                Log.warn(MessageFormat.format("Detected hallucinated PR link: {0}, setting to null", analysisResult.prLink()));
                analysisResult = new AnalysisResult(
                    analysisResult.promote(),
                    analysisResult.confidence(),
                    analysisResult.analysis(),
                    analysisResult.rootCause(),
                    analysisResult.remediation(),
                    null  // Clear hallucinated link
                );
            }
            
            // Convert AnalysisResult to KubernetesAgentResponse
            KubernetesAgentResponse response = new KubernetesAgentResponse(
                analysisResult.analysis(),
                analysisResult.rootCause(),
                analysisResult.remediation(),
                analysisResult.prLink(),
                analysisResult.promote(),
                analysisResult.confidence()
            );
            
            Log.info("Multi-agent workflow completed successfully");
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

        prompt.append("\n⚠️ CRITICAL: EXECUTE ONE TOOL CALL AT A TIME ⚠️\n");
        prompt.append("⚠️ MAXIMUM 3-4 TOOL CALLS - BE EFFICIENT ⚠️\n\n");
        prompt.append("EFFICIENT SEQUENTIAL EXECUTION RULES:\n");
        prompt.append("1. Call ONE tool and STOP - wait for the result\n");
        prompt.append("2. Review the result before deciding the next step\n");
        prompt.append("3. ALWAYS call inspectResources FIRST to discover actual pod names\n");
        prompt.append("4. NEVER hallucinate or guess pod/resource names\n");
        prompt.append("5. Use actual names from previous tool results\n");
        prompt.append("6. Maximum 3-4 tool calls total (not 5-7!)\n");
        prompt.append("7. Each tool can only be called ONCE with the same parameters\n");
        prompt.append("8. Get logs from ONE representative pod per group (not all pods)\n");
        prompt.append("9. Skip getEvents if pods are Running/Ready\n");
        prompt.append("10. Return immediately once you have pod names and logs from both stable and canary\n\n");
        prompt.append("EFFICIENT WORKFLOW (3-4 calls):\n");
        prompt.append("1. inspectResources for stable pods\n");
        prompt.append("2. inspectResources for canary pods\n");
        prompt.append("3. getLogs from ONE stable pod\n");
        prompt.append("4. getLogs from ONE canary pod\n");
        prompt.append("→ RETURN IMMEDIATELY\n\n");
        prompt.append("The multi-agent workflow will:\n");
        prompt.append("1. DiagnosticAgent: Gather minimum necessary data efficiently (3-4 tool calls)\n");
        prompt.append("2. AnalysisAgent: Analyze the gathered data\n");
        prompt.append("3. RemediationAgent: Implement fixes if needed\n");
        
        return prompt.toString();
    }
    
    /**
     * Detect hallucinated URLs that the LLM may have invented.
     * Common patterns include example.com, example/repo, or generic placeholder URLs.
     *
     * @param url The URL to validate
     * @return true if the URL appears to be hallucinated, false otherwise
     */
    private boolean isHallucinatedUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        // Detect common hallucination patterns
        boolean isHallucinated = url.contains("example.com") ||
                                 url.contains("example/repo") ||
                                 url.matches(".*github\\.com/[^/]+/repo/.*");
        
        if (isHallucinated) {
            Log.debug(MessageFormat.format("URL matched hallucination pattern: {0}", url));
        }
        
        return isHallucinated;
    }
}