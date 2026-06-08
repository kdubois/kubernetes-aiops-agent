package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.remediation.GitHubRestClient;
import dev.kevindubois.rollout.agent.workflow.RemediationLoop;
import dev.langchain4j.service.output.OutputParsingException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

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

    @Inject
    @RestClient
    GitHubRestClient githubClient;

    public void triggerIfNeeded(AnalysisResult result, String prompt, String repoUrl, String baseBranch) {
        if (result.promote() || repoUrl == null || repoUrl.isEmpty()) {
            Log.debug("Skipping remediation - promote=true or no repoUrl configured");
            return;
        }

        Log.info("Triggering async remediation for rollback decision");
        activityEvents.remediationTriggered();

        if (isOperationalIssue(result.rootCause())) {
            Log.info("Operational issue detected — creating GitHub issue directly (no LLM)");
            CompletableFuture.runAsync(() -> createIssueDeterministically(result, repoUrl));
        } else {
            String enrichedPrompt = prompt + sourceCodePrefetcher.prefetchSourceCode(
                    prompt + "\n" + result, repoUrl, baseBranch);
            CompletableFuture.runAsync(() -> executeWithRetry(enrichedPrompt, result, repoUrl, baseBranch));
        }
    }

    /**
     * Create a GitHub issue directly without LLM involvement.
     * Used for operational issues (OOM, memory leaks, etc.) where the analysis
     * result already contains all the information needed.
     */
    private void createIssueDeterministically(AnalysisResult result, String repoUrl) {
        try {
            String[] ownerRepo = extractOwnerAndRepo(repoUrl);
            String token = System.getenv("GITHUB_TOKEN");
            String authHeader = "Bearer " + (token != null ? token : "");

            String title = "Canary Deployment Failed: " + truncate(result.rootCause(), 100);
            String body = String.format("""
                    ## Root Cause Analysis
                    %s

                    ## Analysis Details
                    %s

                    ## Related Kubernetes Resources
                    - **Root cause type**: Operational issue (automated PR not applicable)

                    ---
                    *This issue was automatically created by Kubernetes AI Agent*
                    *Please review and take appropriate action*
                    """,
                    result.rootCause() != null ? result.rootCause() : "Unknown",
                    truncate(result.analysis(), 2000));

            GitHubRestClient.CreateIssueRequest request = new GitHubRestClient.CreateIssueRequest(
                    title, body,
                    new String[]{"deployment-failure", "canary"},
                    new String[]{"kdubois"});

            GitHubRestClient.GitHubIssue issue = githubClient.createIssue(
                    ownerRepo[0], ownerRepo[1], authHeader, request);

            Log.infof("Created GitHub issue deterministically: %s", issue.html_url());
            activityEvents.remediationCompleted(issue.html_url());

        } catch (Exception e) {
            Log.error("Failed to create GitHub issue", e);
            activityEvents.remediationFailed("Failed to create issue: " + e.getMessage());
        }
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
                    continue;
                }

                Log.error("Remediation failed after all attempts", e);
                if (isOutputParsingFailure(e)) {
                    Log.info("Falling back to GitHub issue creation after LLM failure");
                    createIssueDeterministically(result, repoUrl);
                } else {
                    activityEvents.remediationFailed(e.getMessage());
                }
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

    private static String[] extractOwnerAndRepo(String repoUrl) {
        String cleaned = repoUrl.replace("https://github.com/", "").replace(".git", "");
        return cleaned.split("/", 2);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static boolean isOperationalIssue(String rootCause) {
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
