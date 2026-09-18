package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;
import dev.kevindubois.rollout.agent.remediation.RemediationOrchestrator;
import dev.kevindubois.rollout.agent.analysis.AnalysisOrchestratorAgent;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import io.quarkus.logging.Log;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.temporal.ChronoUnit;

/**
 * Single entry point for rollout analysis used by both the REST endpoint and A2A executor.
 * Runs the workflow with retry, publishes activity events, and triggers remediation on rollback.
 */
@ApplicationScoped
public class AnalysisService {

    @Inject
    AnalysisOrchestratorAgent analysisOrchestratorAgent;

    @Inject
    RemediationOrchestrator remediationOrchestrator;

    @Inject
    ActivityEvents activityEvents;

    /**
     * Execute the full analysis pipeline: workflow + events + remediation trigger.
     *
     * @param memoryId   conversation/session ID for the agent memory
     * @param prompt     assembled prompt including context
     * @param namespace  Kubernetes namespace to analyze
     * @param repoUrl    repository URL for remediation (nullable)
     * @param baseBranch target branch for PRs (defaults to "main")
     * @return the analysis result
     */
    @Retry(maxRetries = 3, delay = 1, delayUnit = ChronoUnit.SECONDS,
           retryOn = RetriableException.class, abortOn = NonRetriableException.class)
    @ExponentialBackoff(factor = 2, maxDelay = 60, maxDelayUnit = ChronoUnit.SECONDS)
    public AnalysisResult analyze(String memoryId, String prompt, String namespace,
                                   String repoUrl, String baseBranch) throws Exception {
        AnalysisResult result = analysisOrchestratorAgent.execute(memoryId, prompt, namespace);

        activityEvents.analysisCompleted(result);

        remediationOrchestrator.triggerIfNeeded(result, prompt, repoUrl, baseBranch);

        Log.info("Analysis pipeline completed successfully");
        return result;
    }
}
