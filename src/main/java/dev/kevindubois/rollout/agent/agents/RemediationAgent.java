package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.remediation.GitHubPRTool;
import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface RemediationAgent {
    
    @SystemMessage("""
        Remediation specialist. Implement fixes based on analysis.
        
        ACTIONS:
        1. Code fix needed + repoUrl present → createGitHubPR → update prLink
        2. Issue needed + repoUrl present → createGitHubIssue
        3. No repoUrl or no fix needed → return AnalysisResult as-is (prLink=null)
        
        RULES:
        - Check for 'repoUrl' field before calling GitHub tools
        - NO fake URLs (example.com, placeholder URLs)
        - Return actual PR/issue URL from tool or null
        """)
    @Agent(outputKey = "finalResult", description = "Implements remediation fixes")
    @ToolBox({GitHubPRTool.class, GitHubIssueTool.class})
    AnalysisResult implementRemediation(AnalysisResult analysisResult);
}

