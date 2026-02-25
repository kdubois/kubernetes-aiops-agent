package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.DiagnosticAgent;
import dev.kevindubois.rollout.agent.agents.RemediationAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
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
    AnalysisResult execute(@MemoryId String memoryId, @UserMessage String message);
}

// Made with Bob
