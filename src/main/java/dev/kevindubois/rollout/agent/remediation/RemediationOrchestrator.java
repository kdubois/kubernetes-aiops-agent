package dev.kevindubois.rollout.agent.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationA2AAgentProducer;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationRequest;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationResponse;
import dev.kevindubois.rollout.agent.utils.TextUtils;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Delegates remediation to the remote remediation agent over A2A after a
 * rollback decision. The coding agent, the sandbox isolation, the test-suite
 * verification and the GitHub work all live in the remote agent — this side
 * only composes the request from the analysis result and reports the outcome
 * through the activity events when the sandbox run finishes.
 *
 * <p>Runs on its own virtual-thread executor with a call timeout: a sandbox
 * run takes minutes and must not occupy a Quarkus core thread, and a hung
 * A2A call must not block the executor forever.
 */
@ApplicationScoped
public class RemediationOrchestrator {

    private static final int MAX_CONTEXT_LENGTH = 4000;
    private static final int CALL_TIMEOUT_MINUTES = 35;

    @Inject
    RemediationA2AAgentProducer remediationAgentProducer;

    @Inject
    ActivityEvents activityEvents;

    @Inject
    ObjectMapper mapper;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public void triggerIfNeeded(AnalysisResult result, String prompt, String repoUrl, String baseBranch) {
        if (result.promote() || repoUrl == null || repoUrl.isEmpty()) {
            Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            return;
        }

        Log.info("Delegating remediation to the remediation agent over A2A");
        activityEvents.remediationTriggered();

        RemediationRequest request = new RemediationRequest(
                repoUrl,
                baseBranch,
                result.issueCategory() != null ? result.issueCategory().name() : null,
                result.summary(),
                result.rootCause(),
                result.analysis(),
                TextUtils.truncate(prompt, MAX_CONTEXT_LENGTH));

        CompletableFuture.runAsync(() -> invokeRemediationAgent(request), executor);
    }

    private void invokeRemediationAgent(RemediationRequest request) {
        try {
            String responseJson = CompletableFuture
                    .supplyAsync(() -> remediationAgentProducer.agent().remediate(toJson(request)), executor)
                    .get(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            RemediationResponse response = mapper.readValue(responseJson, RemediationResponse.class);
            Log.infof("Remediation agent finished: %s %s", response.status(), response.artifactLink());
            if (response.artifactLink() != null && TextUtils.isValidGitHubArtifactUrl(response.artifactLink())) {
                activityEvents.remediationCompleted(response.artifactLink());
            } else if ("rejected".equals(response.status()) || "infra_error".equals(response.status())
                    || "agent_error".equals(response.status())) {
                // no artifact and nothing was created — report it as a failure,
                // not as a completed remediation without an artifact
                activityEvents.remediationFailed(
                        response.status() + (response.message() != null ? ": " + response.message() : ""));
            } else {
                activityEvents.remediationCompleted(null);
            }
        } catch (Exception e) {
            Log.error("A2A remediation failed", e);
            activityEvents.remediationFailed("A2A remediation failed: " + e.getMessage());
        }
    }

    private String toJson(RemediationRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize remediation request", e);
        }
    }
}
