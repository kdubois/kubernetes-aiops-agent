package org.csanchez.rollout.k8sagent.agents;

import org.csanchez.rollout.k8sagent.k8s.K8sTools;
import org.csanchez.rollout.k8sagent.remediation.GitHubPRTool;

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
        2. Gather comprehensive diagnostic data:
             - Pod status and conditions (use debugPod)
             - Recent events (use getEvents)
             - Container logs (use getLogs, include previous=true if crashed)
             - Resource metrics (use getMetrics)
             - Related resources like services, deployments (use inspectResources)
        3. Identify root cause using AI analysis and pattern matching
        4. If a code fix is needed, determine:
             - Which repository to clone
             - Which files need changes
             - Specific code modifications (diffs)
             - Then call createGitHubPR with this information

             IMPORTANT: You provide the WHAT (files to change, code diffs),
             the tool handles the HOW (git clone, branch, commit, push, PR creation)
             using standard libraries. Do NOT generate git commands.
        5. Return a comprehensive report with:
             - Root cause
             - Remediation steps taken
             - PR link (if created)
             - Recommendations for prevention

        Always gather data systematically before making conclusions.
        Be thorough but concise in your analysis.
    """)
	@ToolBox({K8sTools.class, GitHubPRTool.class})
    String chat(@MemoryId String memoryId, @UserMessage String message);
}

