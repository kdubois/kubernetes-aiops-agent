package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.LogsAgent;
import dev.kevindubois.rollout.agent.agents.MetricsAgent;
import dev.langchain4j.agentic.declarative.ParallelAgent;

public interface DataCollectionWorkflow {

    @ParallelAgent(
        description = "Gathers logs and application metrics in parallel from stable and canary pods",
        outputKey = "collectedData",
        subAgents = {
            LogsAgent.class,
            MetricsAgent.class
        }
    )
    String gatherAllData(String message);
}