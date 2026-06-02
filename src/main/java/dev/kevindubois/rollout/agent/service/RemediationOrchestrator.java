package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.workflow.RemediationLoop;
import dev.langchain4j.service.output.OutputParsingException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class RemediationOrchestrator {

    private static final int MAX_ATTEMPTS = 2;

    @Inject
    RemediationLoop remediationLoop;

    @Inject
    SourceCodePrefetcher sourceCodePrefetcher;

    @Inject
    ActivityEvents activityEvents;

    @Inject
    RemediationOutcomeHolder outcomeHolder;

    public void triggerIfNeeded(AnalysisResult result, String prompt, String repoUrl, String baseBranch) {
        if (result.promote() || repoUrl == null || repoUrl.isEmpty()) {
            Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            return;
        }

        Log.info("Triggering async remediation for rollback decision");
        activityEvents.remediationTriggered();

        String enrichedPrompt;
        if (isOperationalIssue(result.rootCause())) {
            Log.info("Operational issue detected, skipping source code pre-fetch — will create issue instead of PR");
            enrichedPrompt = prompt;
        } else {
            enrichedPrompt = prompt + sourceCodePrefetcher.prefetchSourceCode(
                    prompt + "\n" + result, repoUrl, baseBranch);
        }

        CompletableFuture.runAsync(() -> executeWithRetry(enrichedPrompt, result, repoUrl, baseBranch));
    }

    private void executeWithRetry(String prompt, AnalysisResult result, String repoUrl, String baseBranch) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            outcomeHolder.reset();
            try {
                Log.infof("Remediation attempt %d/%d", attempt, MAX_ATTEMPTS);
                RemediationResult outcome = remediationLoop.remediateWithRetry(prompt, result, repoUrl, baseBranch);
                String artifactUrl = (outcome != null) ? outcome.prLink() : null;

                if (artifactUrl == null || artifactUrl.isEmpty()) {
                    artifactUrl = outcomeHolder.getOutcome()
                            .map(RemediationResult::prLink)
                            .orElse(null);
                }

                activityEvents.remediationCompleted(artifactUrl);
                return;
            } catch (Exception e) {
                if (isOutputParsingFailure(e) && tryRecoverFromToolOutcome()) {
                    return;
                }

                if (attempt < MAX_ATTEMPTS) {
                    Log.warnf("Remediation attempt %d failed, retrying: %s", attempt, e.getMessage());
                    activityEvents.remediationRetrying(attempt, e.getMessage());
                    continue;
                }

                Log.error("Remediation failed after all attempts", e);
                activityEvents.remediationFailed(
                        isOutputParsingFailure(e) ? "LLM produced unusable output" : e.getMessage());
            }
        }
    }

    private boolean tryRecoverFromToolOutcome() {
        return outcomeHolder.getOutcome()
                .map(fallback -> {
                    Log.warn("Remediation tool succeeded but LLM output parsing failed; using tool outcome");
                    activityEvents.remediationCompleted(fallback.prLink());
                    return true;
                })
                .orElse(false);
    }

    private static boolean isOutputParsingFailure(Throwable t) {
        while (t != null) {
            if (t instanceof OutputParsingException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private boolean isOperationalIssue(String rootCause) {
        if (rootCause == null || rootCause.isEmpty()) {
            return false;
        }
        String lower = rootCause.toLowerCase();
        return lower.contains("memory leak") || lower.contains("oom") || lower.contains("out of memory")
                || lower.contains("outofmemory") || lower.contains("resource exhaustion")
                || lower.contains("cpu throttl") || lower.contains("disk space")
                || lower.contains("oomkilled") || lower.contains("heap")
                || lower.contains("gc activity") || lower.contains("garbage collect")
                || lower.contains("performance degradation");
    }
}
