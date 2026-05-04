package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.DataCombinerAgent;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.observability.ActivityEventListener;
import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkus.arc.Arc;

/**
 * Main workflow orchestrating the complete Kubernetes rollout analysis.
 * 1. ParallelDataWorkflow: Non-AI agents fetch diagnostics and metrics in parallel
 * 2. DataCombinerAgent: Non-AI agent combines the parallel reports into a single string
 * 3. AnalysisLoop: AI agents analyze the data and make promote/rollback decision
 */
public interface KubernetesWorkflow {

    @AgentListenerSupplier
    static AgentListener listener() {
        return Arc.container().instance(ActivityEventListener.class).get();
    }

    @SequenceAgent(
        description = "Complete Kubernetes rollout analysis workflow",
        outputKey = "analysisResult",
        subAgents = {
            ParallelDataWorkflow.class,
            DataCombinerAgent.class,
            AnalysisLoop.class
        }
    )
    AnalysisResult execute(
        @MemoryId String memoryId,
        @UserMessage String message,
        String repoUrl,
        String baseBranch
    );
}
