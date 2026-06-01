package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.agents.RemediationAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.service.output.OutputParsingException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class RemediationOrchestrator {

    @Inject
    RemediationAgent remediationAgent;

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

        CompletableFuture.runAsync(() -> {
            outcomeHolder.reset();
            try {
                Log.info("Starting async remediation");
                remediationAgent.implementRemediation(enrichedPrompt, result, repoUrl, baseBranch);
            } catch (Exception e) {
                if (isOutputParsingFailure(e) && tryRecoverFromToolOutcome()) {
                    return;
                }
                if (e instanceof OutputParsingException) {
                    Log.error("RemediationAgent failed to parse LLM output", e);
                    activityEvents.remediationFailed("Output parsing error: " + e.getMessage());
                } else {
                    Log.error("Async remediation failed (non-critical)", e);
                    activityEvents.remediationFailed("Exception: " + e.getMessage());
                }
            }
        });
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
