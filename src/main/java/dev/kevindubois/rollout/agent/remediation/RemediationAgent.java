package dev.kevindubois.rollout.agent.remediation;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.remediation.a2a.RemediationRequest;
import dev.kevindubois.rollout.agent.utils.TextUtils;
import dev.langchain4j.agentic.Agent;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;

/**
 * Declarative remediation step at the end of the analysis sequence. When the
 * analysis rolls back a code bug and a repository is configured, it composes
 * the {@link RemediationRequest} and hands it to {@link RemediationDelegator},
 * which calls the remote remediation agent over A2A on a background thread —
 * the analysis decision returns to Argo Rollouts immediately while the
 * sandboxed fix runs.
 *
 * <p>Healthy rollouts and analyses without a repository are skipped inside
 * the method body: the agentic framework in this version ignores
 * {@code optional = true} on non-AI agents and fails on missing scope
 * arguments, so the callers must always pass non-null values (empty string
 * for "not configured") and the gate lives here.
 */
public class RemediationAgent {

    private static final int MAX_CONTEXT_LENGTH = 4000;

    private RemediationAgent() {
    }

    @Agent(description = "Delegates code-bug fixes to the sandboxed remediation agent over A2A "
            + "after a rollback decision; returns null for healthy rollouts or when no repository is configured",
            outputKey = "remediationOutcome")
    public static String remediate(AnalysisResult analysisResult, String repoUrl, String baseBranch, String message) {
        if (analysisResult == null || analysisResult.promote() || repoUrl == null || repoUrl.isBlank()) {
            Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            return null;
        }

        RemediationRequest request = new RemediationRequest(
                repoUrl,
                baseBranch != null && !baseBranch.isBlank() ? baseBranch : "main",
                analysisResult.issueCategory() != null ? analysisResult.issueCategory().name() : null,
                analysisResult.summary(),
                analysisResult.rootCause(),
                analysisResult.analysis(),
                TextUtils.truncate(message, MAX_CONTEXT_LENGTH));

        return Arc.container().instance(RemediationDelegator.class).get().delegate(request);
    }
}
