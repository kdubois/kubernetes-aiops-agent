package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.remediation.GitHubPRTool;
import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface RemediationAgent {
    
    @SystemMessage("""
        You are a remediation specialist.
        
        Based on the analysis, implement fixes:
        1. If code fix needed:
           - Determine repository, files, changes
           - Call createGitHubPR with the fix
           - Update the prLink field with the PR URL
        2. If issue reporting needed:
           - Call createGitHubIssue with details
        3. Return the updated AnalysisResult with PR link
        
        Use GitHub tools to implement fixes.
        Return the same AnalysisResult but with prLink updated if a PR was created.
        """)
    @Agent(outputKey = "finalResult", description = "Implements remediation fixes")
    @ToolBox({GitHubPRTool.class, GitHubIssueTool.class})
    AnalysisResult implementRemediation(AnalysisResult analysisResult);
}

