package dev.kevindubois.rollout.agent.a2a;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.kevindubois.rollout.agent.workflow.KubernetesWorkflow;
import dev.kevindubois.rollout.agent.agents.RemediationAgent;
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
    
    @Inject
    RemediationAgent remediationAgent;
     
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
            // Extract context values for later use
            Map<String, Object> context = request.context();
            String repoUrl = context != null ? (String) context.get("repoUrl") : null;
            String baseBranch = context != null ? (String) context.get("baseBranch") : "main";
            
            Log.info(MessageFormat.format("Context - repoUrl: {0}, baseBranch: {1}", repoUrl, baseBranch));
            
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
                () -> kubernetesWorkflow.execute(memoryId, prompt, repoUrl, baseBranch),
                "Multi-agent workflow analysis"
            );
            
            // Add context to result if not already present (fallback mechanism)
            if (analysisResult.repoUrl() == null && repoUrl != null) {
                analysisResult = new AnalysisResult(
                    analysisResult.promote(),
                    analysisResult.confidence(),
                    analysisResult.analysis(),
                    analysisResult.rootCause(),
                    analysisResult.remediation(),
                    analysisResult.prLink(),
                    repoUrl,
                    baseBranch
                );
            }
            
            // Validate PR link is not hallucinated
            if (analysisResult.prLink() != null && isHallucinatedUrl(analysisResult.prLink())) {
                Log.warn(MessageFormat.format("Detected hallucinated PR link: {0}, setting to null", analysisResult.prLink()));
                analysisResult = new AnalysisResult(
                    analysisResult.promote(),
                    analysisResult.confidence(),
                    analysisResult.analysis(),
                    analysisResult.rootCause(),
                    analysisResult.remediation(),
                    null,  // Clear hallucinated link
                    analysisResult.repoUrl(),
                    analysisResult.baseBranch()
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
            
            // Trigger async remediation if needed (fire-and-forget)
            final AnalysisResult finalResult = analysisResult;
            final String finalPrompt = buildPrompt(request);
            if (!finalResult.promote() && repoUrl != null && !repoUrl.isEmpty()) {
                Log.info("Triggering async remediation for rollback decision");
                CompletableFuture.runAsync(() -> {
                    try {
                        Log.info("Starting async remediation");
                        AnalysisResult remediationResult = remediationAgent.implementRemediation(
                            finalPrompt, finalResult, repoUrl, baseBranch
                        );
                        if (remediationResult.prLink() != null && !remediationResult.prLink().isEmpty()) {
                            Log.info(MessageFormat.format(
                                "Async remediation completed - GitHub artifact created: {0}",
                                remediationResult.prLink()
                            ));
                        } else {
                            Log.info("Async remediation completed - no GitHub artifact created");
                        }
                    } catch (Exception e) {
                        Log.error("Async remediation failed (non-critical)", e);
                    }
                });
            } else {
                Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            }
            
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

        prompt.append("\n⚠️ CRITICAL: USE getCanaryDiagnostics TOOL FIRST ⚠️\n");
        prompt.append("⚠️ MAXIMUM 1-2 TOOL CALLS - BE EFFICIENT ⚠️\n\n");
        prompt.append("EFFICIENT WORKFLOW:\n");
        prompt.append("1. ALWAYS call getCanaryDiagnostics(namespace, containerName, tailLines) FIRST\n");
        prompt.append("   - This fetches BOTH stable AND canary pod info and logs in ONE call\n");
        prompt.append("   - Returns pod names, phases, ready status, and logs for both stable and canary\n");
        prompt.append("   - Container name can be null/empty for auto-detection\n");
        prompt.append("2. Analyze the results from getCanaryDiagnostics\n");
        prompt.append("3. Only call additional tools if absolutely necessary (e.g., getEvents for pod failures)\n");
        prompt.append("4. Return your analysis immediately\n\n");
        prompt.append("RULES:\n");
        prompt.append("- Call ONE tool at a time and wait for results\n");
        prompt.append("- NEVER hallucinate or guess pod/resource names\n");
        prompt.append("- Use actual names from tool results\n");
        prompt.append("- Skip getEvents if pods are Running/Ready\n");
        prompt.append("- Each tool can only be called ONCE with the same parameters\n\n");
        prompt.append("The multi-agent workflow will:\n");
        prompt.append("1. DiagnosticAgent: Gather data efficiently (1-2 tool calls using getCanaryDiagnostics)\n");
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