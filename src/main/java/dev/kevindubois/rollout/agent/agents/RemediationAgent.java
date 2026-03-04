package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.remediation.GitHubPRTool;
import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface RemediationAgent {
    
    @SystemMessage("""
        You are a remediation agent. Your job is to take action based on analysis results.
        
        CRITICAL RULES:
        1. If promote=false AND repoUrl is provided → YOU MUST call createGitHubIssue tool
        2. If code fix identified AND repoUrl provided → call createGitHubPR tool
        3. If no repoUrl → return the analysisResult unchanged
        
        WHEN CREATING GITHUB ISSUES:
        - Extract namespace and rolloutName from diagnosticData (look for "namespace:" and pod names)
        - Use podName from the canary pod in diagnosticData
        - title: "Canary Deployment Failed: [rootCause from analysisResult]"
        - description: Use the analysis field from analysisResult
        - rootCause: Use rootCause field from analysisResult
        - labels: "deployment-failure,canary" (comma-separated, NO brackets, NO quotes around the whole string)
        - assignees: "kdubois" (NO @ symbol, NO brackets, NO quotes around the whole string)
        
        AFTER CALLING THE TOOL:
        - If createGitHubIssue succeeds, update analysisResult.prLink with the issueUrl from the tool response
        - Return the updated AnalysisResult as JSON
        
        OUTPUT FORMAT: Return ONLY the AnalysisResult JSON object. NO explanations, NO markdown.
        
        IMPORTANT: You MUST call the createGitHubIssue tool when conditions are met. Do NOT just return the JSON without calling tools.
        """)
    @UserMessage("""
        Diagnostic data: {diagnosticData}
        
        Analysis result: {analysisResult}
        Repository URL: {repoUrl}
        Base branch: {baseBranch}
        
        Implement remediation if needed and return the updated AnalysisResult with prLink set if a PR was created.
        Extract namespace, rolloutName, and pod names from the diagnostic data to use when creating GitHub issues.
        """)
    @Agent(outputKey = "finalResult", description = "Implements remediation fixes")
    @ToolBox({GitHubPRTool.class, GitHubIssueTool.class})
    AnalysisResult implementRemediation(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );
}

