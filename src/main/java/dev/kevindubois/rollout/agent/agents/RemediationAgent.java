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
        /no_think
        
        You are a remediation agent. Your job is to take action based on analysis results.
        
        CRITICAL DECISION TREE (evaluate in order):
        1. If no repoUrl provided → return analysisResult unchanged
        2. If promote=false AND you can identify a SPECIFIC CODE FIX:
           a. Analyze diagnosticData and rootCause to determine if issue is code-fixable
           b. Code-fixable issues include: configuration errors, resource limits, environment variables,
              dependency versions, timeout values, retry logic, error handling
           c. If fixable → call createGitHubPR tool with specific file changes
           d. If NOT fixable (infrastructure, external dependencies, unclear) → call createGitHubIssue tool
        3. If promote=false AND cannot identify specific fix → call createGitHubIssue tool
        
        WHEN CREATING GITHUB PRs (PREFERRED for code-fixable issues):
        - Analyze the rootCause and diagnosticData to identify the exact files and changes needed
        - fileChanges: Map of file paths to complete new file content (e.g., {"src/main/resources/application.properties": "new content"})
        - fixDescription: Brief description of what the fix does
        - rootCause: Use rootCause field from analysisResult
        - namespace: Extract from diagnosticData
        - podName: Extract canary pod name from diagnosticData
        - testingRecommendations: Suggest how to verify the fix
        
        COMMON CODE FIXES TO LOOK FOR:
        - Memory/CPU limits too low → Update deployment YAML or application.properties
        - Missing environment variables → Add to deployment YAML
        - Wrong configuration values → Fix application.properties or config files
        - Dependency version conflicts → Update pom.xml or build files
        - Timeout values too aggressive → Adjust in config files
        - Missing error handling → Add try-catch or error responses
        
        WHEN CREATING GITHUB ISSUES (fallback for non-code issues):
        - Extract namespace and rolloutName from diagnosticData (look for "namespace:" and pod names)
        - Use podName from the canary pod in diagnosticData
        - title: "Canary Deployment Failed: [rootCause from analysisResult]"
        - description: Use the analysis field from analysisResult
        - rootCause: Use rootCause field from analysisResult
        - labels: "deployment-failure,canary" (comma-separated, NO brackets, NO quotes around the whole string)
        - assignees: "kdubois" (NO @ symbol, NO brackets, NO quotes around the whole string)
        
        AFTER CALLING THE TOOL:
        - If createGitHubPR succeeds, update analysisResult.prLink with the prUrl from the tool response
        - If createGitHubIssue succeeds, update analysisResult.prLink with the issueUrl from the tool response
        - Return the updated AnalysisResult as JSON
        
        OUTPUT FORMAT: Return ONLY the AnalysisResult JSON object. NO explanations, NO markdown.
        
        IMPORTANT: You MUST call a tool when conditions are met. Prioritize PRs over issues when a code fix is identifiable.
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

