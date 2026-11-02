package dev.kevindubois.rollout.agent.api;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.analysis.AnalysisOutcome;
import dev.kevindubois.rollout.agent.analysis.PauseGate;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.kevindubois.rollout.agent.analysis.AnalysisService;
import dev.langchain4j.service.output.OutputParsingException;

/**
 * REST endpoint for rollout analysis. Delegates to AnalysisService for the
 * actual pipeline execution and maps the result to the plugin JSON contract.
 */
@Path("/analyze")
public class KubernetesAgentResource {

    @Inject
    AnalysisService analysisService;

    @Inject
    ActivityEvents activityEvents;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(KubernetesAgentRequest request) {
        Log.infof("Received analysis request from user: %s", request.userId());
        activityEvents.requestStarted("User: " + request.userId());

        try {
            Map<String, Object> context = request.context();
            String repoUrl = contextValue(context, "repoUrl", null);
            String baseBranch = contextValue(context, "baseBranch", "main");
            String namespace = contextValue(context, "namespace", "default");
            String rolloutName = contextValue(context, "rolloutName", null);
            String stableSelector = contextValue(context, "stableSelector", "role=stable");
            String canarySelector = contextValue(context, "canarySelector", "role=canary");
            PauseGate pauseGate = PauseGate.fromMap(context);
            String prompt = buildPrompt(request);

            AnalysisOutcome outcome = analysisService.analyze(
                    prompt, namespace, stableSelector, canarySelector, rolloutName, repoUrl, baseBranch, pauseGate);
            AnalysisResult result = outcome.result();

            KubernetesAgentResponse response = new KubernetesAgentResponse(
                    result.analysis(),
                    result.rootCause(),
                    result.remediation(),
                    result.promote(),
                    result.confidence(),
                    outcome.pausedRollout(),
                    null
            );

            return Response.ok(response).build();

        } catch (OutputParsingException e) {
            Log.error("LLM output parsing failed for user: " + request.userId(), e);
            activityEvents.requestFailed("Output parsing: " + e.getMessage());
            return errorResponse(Status.INTERNAL_SERVER_ERROR,
                    "LLM returned unparsable output", "OutputParsingException");

        } catch (TimeoutException e) {
            // Currently aspirational — no explicit timeout in AnalysisService, but upstream
            // HTTP clients or LLM providers may throw this via wrapped exceptions.
            Log.error("Analysis timed out for user: " + request.userId(), e);
            activityEvents.requestFailed("Timeout: " + e.getMessage());
            return errorResponse(Status.GATEWAY_TIMEOUT,
                    "Analysis timed out", "TimeoutException");

        } catch (Exception e) {
            Log.error("Analysis failed for user: " + request.userId(), e);
            activityEvents.requestFailed(e.getMessage());
            return errorResponse(Status.INTERNAL_SERVER_ERROR,
                    "Error: " + e.getMessage(), e.getClass().getSimpleName());
        }
    }

    private Response errorResponse(Status status, String error, String rootCause) {
        KubernetesAgentResponse body = new KubernetesAgentResponse(
                error, rootCause,
                "Unable to provide remediation due to API error. Please try again.",
                false, 0, null, error);
        return Response.status(status).entity(body).build();
    }

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

    private static String contextValue(Map<String, Object> context, String key, String defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
