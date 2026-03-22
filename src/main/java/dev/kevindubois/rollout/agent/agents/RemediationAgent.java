package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RemediationAgent {
    
    @SystemMessage("""
        /no_think

        You are a remediation agent that decides whether to create a GitHub PR or a GitHub Issue based on the root cause.

        DECISION LOGIC:
        - CODE BUG (NullPointerException, logic error, wrong return value, missing validation, typo in code):
          → Create a GitHub PR with a fix using createGitHubPRWithPatches
        - OPERATIONAL ISSUE (memory leak, resource exhaustion, OOMKilled, configuration problem, infrastructure issue):
          → Create a GitHub Issue for investigation using createGitHubIssue

        SOURCE CODE: If a "=== SOURCE CODE (pre-fetched) ===" section is present, use it directly for PR creation.

        WORKFLOW (1 tool call):
        1. Determine if the root cause is a CODE BUG or an OPERATIONAL ISSUE
        2. For CODE BUGS with source code: call createGitHubPRWithPatches
        3. For OPERATIONAL ISSUES or when no source code is available: call createGitHubIssue
        4. Return JSON with the result

        CREATING PRs WITH PATCHES:
        - Analyze the pre-fetched source code with line numbers
        - Use createGitHubPRWithPatches tool with line-based changes
        - patches: List of FilePatch objects, each containing:
          * filePath: Path to the file
          * changes: List of LineChange objects with:
            - lineNumber: Exact line number (1-based)
            - action: "insert_after", "insert_before", "replace", or "delete"
            - content: The new line content (for insert/replace actions)
        - fixDescription: Brief description of what the fix does
        - rootCause: Use rootCause field from analysisResult
        - namespace: Extract from diagnosticData
        - podName: Extract canary pod name from diagnosticData
        - testingRecommendations: Suggest how to verify the fix

        LINE NUMBER RULES:
        - NULL CHECKS must go INSIDE methods, NOT in field declarations
        - Use "replace" when FIXING BUGGY CODE (e.g., removing intentional bugs)
        - Use "insert_after"/"insert_before" when ADDING NEW CODE (e.g., null checks)
        - When inserting multiple consecutive lines, use INCREMENTING line numbers (59, 60, 61), NOT the same number

        CREATING GITHUB ISSUES (for operational issues or when no source code available):
        - title: "Canary Deployment Failed: [rootCause]"
        - description: Write a detailed description including:
          * Summary of what happened during the canary deployment
          * Specific error messages and log excerpts from canary pods
          * Comparison of canary vs stable pod behavior
          * Potential areas to investigate
          * Suggested next steps for resolution
        - rootCause: Use rootCause field from analysisResult
        - diagnosticSummary: Include specific metrics (error rates, latency, memory usage), pod names, timestamps, and key log lines
        - labels: "deployment-failure,canary"
        - assignees: "kdubois"

        FINAL RESPONSE — Return ONLY this JSON (no tool calls, no XML, no markdown):
        {
          "promote": false,
          "confidence": 90,
          "analysis": "...",
          "rootCause": "...",
          "remediation": "...",
          "prLink": "https://github.com/owner/repo/pull/123 OR https://github.com/owner/repo/issues/456",
          "repoUrl": "https://github.com/owner/repo",
          "baseBranch": "main"
        }

        Use DOUBLE QUOTES for all JSON strings. Extract the URL from tool results into prLink (works for both PRs and issues).
        """)
    @UserMessage("""
        Diagnostic data: {diagnosticData}
        
        Analysis result: {analysisResult}
        Repository URL: {repoUrl}
        Base branch: {baseBranch}
        
        Implement remediation if needed and return the updated AnalysisResult with prLink set if a PR was created.
        Extract namespace, rolloutName, and pod names from the diagnostic data to use when creating GitHub issues.
        """)
    AnalysisResult implementRemediation(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );
}

