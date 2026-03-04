package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.DiagnosticAgent;
import dev.kevindubois.rollout.agent.agents.RemediationAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.agentic.declarative.SequenceAgent;

public interface KubernetesWorkflow {
    
    @SequenceAgent(
        description = "Complete Kubernetes rollout analysis and remediation workflow",
        outputKey = "finalResult",
        subAgents = {
            DiagnosticAgent.class,
            AnalysisLoop.class,
            RemediationAgent.class
        }
    )
    AnalysisResult execute(
        @MemoryId String memoryId,
        @UserMessage String message,
        String repoUrl,
        String baseBranch
    );
}
