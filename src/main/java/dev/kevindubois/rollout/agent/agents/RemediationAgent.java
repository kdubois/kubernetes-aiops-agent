package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;
import dev.kevindubois.rollout.agent.remediation.GitHubPatchPRTool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.ToolBox;

public interface RemediationAgent {
    
    @SystemMessage("""
        /no_think
        Remediation agent. Call ONE tool, then return JSON. No reasoning text.

        DECIDE by root cause:
        - Code bug (NPE, logic error, wrong value) → createGitHubPRWithPatches
        - Operational issue (OOM, memory leak, config) → createGitHubIssue

        createGitHubPRWithPatches: Use pre-fetched source code. Fix only the buggy lines.
        Required: repoUrl, patches, fixDescription, rootCause, namespace, podName, testingRecommendations.
        patches format: [{"filePath": "src/.../File.java", "changes": [{"lineNumber": 127, "action": "replace", "content": "    fixed code here;"}]}]
        Actions: "replace" (fix line), "delete" (remove line), "insert_after"/"insert_before" (add line).
        One change per line. Incrementing line numbers for consecutive changes.

        createGitHubIssue:
        Required: repoUrl, title="Canary Deployment Failed: [rootCause]", description (with error details and logs), rootCause, namespace, podName, diagnosticSummary, labels="deployment-failure,canary", assignees="kdubois".

        AFTER tool call, return EXACTLY this JSON:
        {"prLink": "<URL from tool result or null>", "analysis": "<what you did>", "remediation": "<action taken>"}
        """)
    @UserMessage("""
        /no_think
        {diagnosticData}

        Analysis result: {analysisResult}
        Repository: {repoUrl} Branch: {baseBranch}

        Call the appropriate tool and return JSON.
        /no_think
        """)
    @Agent(outputKey = "remediationResult", description = "Implements remediation by creating GitHub PRs or Issues")
    @ToolBox({GitHubPatchPRTool.class, GitHubIssueTool.class})
    @ModelName("remediation")
    RemediationResult implementRemediation(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );
}
