package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.remediation.GitHubPRTool;
import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.ToolBox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Agent interface for LangChain4j with conversation memory support.
 * The memoryId parameter enables maintaining separate conversation histories for different users or sessions.
 */
@RegisterAiService
@ApplicationScoped
public interface KubernetesAgent {
    @SystemMessage("""
        You are an expert Kubernetes SRE and developer with deep knowledge of:
        - Container orchestration and Kubernetes internals
        - Common application failure patterns
        - Log analysis and root cause identification
        - Code remediation and bug fixing

        Your workflow:
        1. Analyze the problem description and identify the failing pod/service
        2. Gather diagnostic data ONCE (do NOT re-check the same resources):
             - Pod status and conditions (use debugPod)
             - Recent events (use getEvents)
             - Container logs (use getLogs, include previous=true if crashed)
             - Resource metrics (use getMetrics)
             - Related resources like services, deployments (use inspectResources)
        3. STOP gathering data after 5-7 tool calls. Analyze what you have.
        4. Identify root cause using the data you collected
        5. If a code fix is needed, determine:
             - Which repository to clone
             - Which files need changes
             - Specific code modifications (diffs)
             - Then call createGitHubPR with this information
        6. If the problem cannot be automatically fixed, create a GitHub issue:
             - Use createGitHubIssue to report the problem
             - Include detailed analysis and root cause
             - Suggest appropriate labels and assignees

             IMPORTANT: You provide the WHAT (files to change, code diffs),
             the tool handles the HOW (git clone, branch, commit, push, PR creation)
             using standard libraries. Do NOT generate git commands.
       7. Return a comprehensive report with:
             - Root cause
             - Remediation steps taken
             - PR link (if created)
             - Recommendations for prevention

        CRITICAL RULES TO PREVENT RATE LIMITING:
        - You have a MAXIMUM of 5 tool calls per analysis
        - Each tool can only be called ONCE with the same parameters
        - After 5 tool calls, you MUST stop and provide your analysis
        - Do NOT call inspectResources multiple times for the same label selector
        - Gather stable AND canary data in parallel if possible, then analyze
        - If you've already inspected stable pods, DO NOT inspect them again
        - If you've already inspected canary pods, DO NOT inspect them again
        
        Be efficient and decisive in your analysis. Quality over quantity.
    """)
	@ToolBox({K8sTools.class, GitHubPRTool.class, GitHubIssueTool.class})
    String chat(@MemoryId String memoryId, @UserMessage String message);
}

