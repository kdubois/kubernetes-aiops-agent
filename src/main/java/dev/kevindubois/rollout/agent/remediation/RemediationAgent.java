package dev.kevindubois.rollout.agent.remediation;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationRequest;
import dev.kevindubois.rollout.agent.utils.TextUtils;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Triggers remediation after a rollback decision. When the final analysis
 * rolled back a code bug and a repository is configured, composes the
 * {@link RemediationRequest} and hands it to {@link RemediationDelegator},
 * which calls the remote remediation agent over A2A on a background thread —
 * the analysis decision returns to Argo Rollouts immediately while the
 * sandboxed fix runs.
 *
 * <p>Called by {@code AnalysisService} after the decision event is published,
 * so the remediation event appears after the analysis narrative in the
 * activity feed and uses the final (post-escalation) result. Healthy rollouts
 * and analyses without a repository are skipped.
 */
@ApplicationScoped
public class RemediationAgent {

    private static final int MAX_CONTEXT_LENGTH = 4000;

    @Inject
    RemediationDelegator remediationDelegator;

    public void remediate(AnalysisResult analysisResult, String repoUrl, String baseBranch, String message) {
        if (analysisResult == null || analysisResult.promote() || repoUrl == null || repoUrl.isBlank()) {
            Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            return;
        }

        RemediationRequest request = new RemediationRequest(
                repoUrl,
                baseBranch != null && !baseBranch.isBlank() ? baseBranch : "main",
                analysisResult.issueCategory() != null ? analysisResult.issueCategory().name() : null,
                analysisResult.summary(),
                analysisResult.rootCause(),
                analysisResult.analysis(),
                TextUtils.truncate(message, MAX_CONTEXT_LENGTH));

        remediationDelegator.delegate(request);
    }
}
