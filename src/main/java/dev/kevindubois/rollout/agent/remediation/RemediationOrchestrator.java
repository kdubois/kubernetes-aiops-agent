package dev.kevindubois.rollout.agent.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationA2AAgentProducer;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationRequest;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationResponse;
import dev.kevindubois.rollout.agent.utils.TextUtils;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ExecutorService;

/**
 * Delegates remediation to the remote remediation agent over A2A after a
 * rollback decision. The coding agent, the sandbox isolation, the test-suite
 * verification and the GitHub work all live in the remote agent — this side
 * only composes the request from the analysis result and reports the outcome
 * through the activity events when the sandbox run finishes.
 */
@ApplicationScoped
public class RemediationOrchestrator {

    private static final int MAX_ERROR_LOG_LENGTH = 4000;

    @Inject
    RemediationA2AAgentProducer remediationAgentProducer;

    @Inject
    ActivityEvents activityEvents;

    @Inject
    ObjectMapper mapper;

    @Inject
    ExecutorService executor;

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
                TextUtils.truncate(prompt, MAX_ERROR_LOG_LENGTH));

        executor.execute(() -> {
            try {
                String responseJson = remediationAgentProducer.agent().remediate(
                        mapper.writeValueAsString(request));
                RemediationResponse response = mapper.readValue(responseJson, RemediationResponse.class);
                Log.infof("Remediation agent finished: %s %s", response.status(), response.artifactLink());
                activityEvents.remediationCompleted(
                        TextUtils.isValidGitHubArtifactUrl(response.artifactLink())
                                ? response.artifactLink()
                                : null);
            } catch (Exception e) {
                Log.error("A2A remediation failed", e);
                activityEvents.remediationFailed("A2A remediation failed: " + e.getMessage());
            }
        });
    }
}
