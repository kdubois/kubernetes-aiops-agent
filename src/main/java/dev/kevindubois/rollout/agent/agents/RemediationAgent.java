package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.RemediationResult;
import dev.kevindubois.rollout.agent.remediation.GitHubPatchPRTool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.ToolBox;

/**
 * Creates GitHub PRs to fix code bugs found during canary analysis.
 * Only invoked for code-level issues (NPE, logic errors, etc.).
 * Operational issues (OOM, memory leaks) are handled deterministically
 * by the orchestrator without LLM involvement.
 */
public interface RemediationAgent {
    
    @SystemMessage("""
        /no_think
        Remediation agent. Call createGitHubPRWithPatches ONCE, then return JSON. No reasoning text.

        createGitHubPRWithPatches: Fix only the buggy line(s) in pre-fetched source code.
        Required: repoUrl, patches, fixDescription, rootCause, namespace, podName, testingRecommendations.
        patches format: [{"filePath": "src/.../File.java", "changes": [{"lineNumber": 42, "action": "replace", "content": "    fixed code;"}]}]
        Actions: "replace" (fix line), "delete" (remove line), "insert_after"/"insert_before" (add line).
        One change per line. Fix the actual buggy line, not surrounding control flow.

        AFTER the tool call, return EXACTLY this JSON in your response body (not in thinking/reasoning):
        {"prLink": "<URL from tool result>", "analysis": "<what you did>", "remediation": "<action taken>"}
        """)
    @UserMessage("""
        /no_think
        {diagnosticData}

        Analysis result: {analysisResult}
        Repository: {repoUrl} Branch: {baseBranch}

        Call createGitHubPRWithPatches and return JSON.
        /no_think
        """)
    @Agent(outputKey = "remediationResult", description = "Creates GitHub PRs to fix code bugs")
    @ToolBox(GitHubPatchPRTool.class)
    @ModelName("remediation")
    RemediationResult implementRemediation(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );
}
