package dev.kevindubois.rollout.agent.a2a;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.KubernetesAgentRequest;
import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;
import dev.kevindubois.rollout.agent.service.ActivityEvents;
import dev.kevindubois.rollout.agent.service.AnalysisService;
import dev.langchain4j.service.output.OutputParsingException;

/**
 * REST endpoint for rollout analysis. Delegates to AnalysisService for the
 * actual pipeline execution and maps the result to the plugin JSON contract.
 */
@Path("/a2a")
public class KubernetesAgentResource {

    @Inject
    AnalysisService analysisService;

    @Inject
    ActivityEvents activityEvents;

    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze(KubernetesAgentRequest request) {
        Log.infof("Received analysis request from user: %s", request.userId());
        activityEvents.requestStarted("User: " + request.userId());

        try {
            Map<String, Object> context = request.context();
            String repoUrl = context != null ? (String) context.get("repoUrl") : null;
            String baseBranch = context != null ? (String) context.get("baseBranch") : "main";
            String namespace = context != null ? (String) context.get("namespace") : "default";
            String prompt = buildPrompt(request);
            String memoryId = request.getEffectiveMemoryId();

            AnalysisResult result = analysisService.analyze(memoryId, prompt, namespace, repoUrl, baseBranch);

            KubernetesAgentResponse response = new KubernetesAgentResponse(
                    result.analysis(),
                    result.rootCause(),
                    result.remediation(),
                    null,
                    result.promote(),
                    result.confidence()
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

    private Response errorResponse(Status status, String analysis, String rootCause) {
        KubernetesAgentResponse body = new KubernetesAgentResponse(
                analysis, rootCause,
                "Unable to provide remediation due to API error. Please try again.",
                null, true, 0);
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
}
