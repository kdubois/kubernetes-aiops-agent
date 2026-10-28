package dev.kevindubois.rollout.agent.remediation.a2a;

/**
 * Remediation request sent to the remediation-agent over A2A.
 *
 * @param repoUrl       https URL of the repository that failed the canary
 * @param baseBranch    branch the fix should target
 * @param issueCategory CODE_BUG or OPERATIONAL, decides PR or issue handling
 * @param title         short summary for the PR or issue
 * @param rootCause     root cause from the canary analysis
 * @param analysis      full analysis text from the canary analysis
 * @param diagnosticContext rollout context and diagnostics from the analysis prompt
 */
public record RemediationRequest(
        String repoUrl,
        String baseBranch,
        String issueCategory,
        String title,
        String rootCause,
        String analysis,
        String diagnosticContext) {
}
