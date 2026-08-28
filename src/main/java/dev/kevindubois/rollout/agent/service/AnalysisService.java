package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.utils.RetryHelper;
import dev.kevindubois.rollout.agent.workflow.KubernetesWorkflow;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Single entry point for rollout analysis used by both the REST endpoint and A2A executor.
 * Runs the workflow with retry, publishes activity events, and triggers remediation on rollback.
 */
@ApplicationScoped
public class AnalysisService {

    @Inject
    KubernetesWorkflow kubernetesWorkflow;

    @Inject
    RemediationOrchestrator remediationOrchestrator;

    @Inject
    ActivityEvents activityEvents;

    /**
     * Execute the full analysis pipeline: workflow + events + remediation trigger.
     *
     * @param memoryId  conversation/session ID for the agent memory
     * @param prompt    assembled prompt including context
     * @param repoUrl   repository URL for remediation (nullable)
     * @param baseBranch target branch for PRs (defaults to "main")
     * @return the analysis result
     */
    public AnalysisResult analyze(String memoryId, String prompt, String repoUrl, String baseBranch) throws Exception {
        AnalysisResult result = RetryHelper.executeWithRetry(
                () -> kubernetesWorkflow.execute(memoryId, prompt),
                "Multi-agent workflow analysis"
        );

        activityEvents.analysisCompleted(result);

        remediationOrchestrator.triggerIfNeeded(result, prompt, repoUrl, baseBranch);

        Log.info("Analysis pipeline completed successfully");
        return result;
    }
}
