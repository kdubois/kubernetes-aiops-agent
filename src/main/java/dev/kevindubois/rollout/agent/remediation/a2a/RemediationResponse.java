package dev.kevindubois.rollout.agent.remediation.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result of a remediation run, returned by the remediation-agent as the A2A
 * task artifact.
 *
 * @param status       success | tests_failed | no_changes | agent_error | rejected | issue
 * @param prLink       GitHub PR URL when a verified fix was proposed, else null
 * @param issueLink    GitHub issue URL when an issue was opened, else null
 * @param branch       pushed branch name on success, else null
 * @param filesChanged number of files the coding agent changed
 * @param testSummary  test suite summary from the sandbox verification
 * @param sandbox      name of the sandbox that ran the remediation
 * @param message      human-readable detail for the non-happy paths
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemediationResponse(
        String status,
        String prLink,
        String issueLink,
        String branch,
        int filesChanged,
        String testSummary,
        String sandbox,
        String message) {

    public String artifactLink() {
        return prLink != null ? prLink : issueLink;
    }
}
