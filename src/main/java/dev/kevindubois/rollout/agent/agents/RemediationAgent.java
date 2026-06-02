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
        Remediation agent. Call ONE tool immediately. No reasoning or explanation text.

        DECIDE by root cause:
        - Code bug (NPE, logic error, wrong value) → createGitHubPRWithPatches
        - Operational issue (OOM, memory leak, config) → createGitHubIssue

        createGitHubPRWithPatches: Fix only the buggy line(s) in pre-fetched source code.
        Required: repoUrl, patchesJson, fixDescription, rootCause, namespace, podName, testingRecommendations.
        patchesJson: a JSON array string, e.g. [{"filePath":"src/.../File.java","changes":[{"lineNumber":42,"action":"replace","content":"    fixed code;"}]}]
        Actions: "replace", "delete", "insert_after", "insert_before". One change per line. Use as many changes as needed to produce correct code.

        PATCH RULES (CRITICAL - violating these produces compilation errors):
        - Fix the ACTUAL line that causes the error, not the if-statement wrapping it.
          WRONG: replacing/deleting `if (flag) {` — this leaves the block body running unconditionally.
          RIGHT: replacing the buggy line inside the block (e.g. replace `x = null.length()` with `x = safe.length()`).
        - To remove an entire block: delete ALL lines from the opening `if` through its closing `}`, including every line of the body. You need one change per line.
        - NEVER replace an `if(...)` line with a non-if line while leaving the block body and closing brace. This makes the block body run unconditionally and leaves orphaned braces.
        - Preserve indentation: content must match the original file's indent style.
        - The patch must produce code that COMPILES and FIXES the bug. Mentally trace through the patched code to verify.

        createGitHubIssue:
        Required: repoUrl, title="Canary Deployment Failed: [rootCause]", description, rootCause, namespace, podName, diagnosticSummary, labels="deployment-failure,canary", assignees="kdubois".

        AFTER tool call, return EXACTLY this JSON in your response body (not in thinking/reasoning):
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
