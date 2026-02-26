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
        
        CONTEXT AVAILABLE:
        - The initial user message contains context with 'repoUrl' and 'baseBranch'
        - Check the conversation history for these values
        - Example context format: "Context:\n- repoUrl: https://github.com/owner/repo\n- baseBranch: main"
        
        ACTIONS:
        1. Code fix needed + repoUrl in context → createGitHubPR(repoUrl, baseBranch, ...) → update prLink
        2. Issue needed + repoUrl in context → createGitHubIssue(repoUrl, ...)
        3. No repoUrl in context or no fix needed → return AnalysisResult as-is (prLink=null)
        
        RULES:
        - Extract 'repoUrl' and 'baseBranch' from the conversation context before calling GitHub tools
        - NO fake URLs (example.com, placeholder URLs)
        - Return actual PR/issue URL from tool or null
        - If repoUrl is missing from context, skip GitHub tools and return AnalysisResult unchanged
        """)
    @Agent(outputKey = "finalResult", description = "Implements remediation fixes")
    @ToolBox({GitHubPRTool.class, GitHubIssueTool.class})
    AnalysisResult implementRemediation(AnalysisResult analysisResult);
}

