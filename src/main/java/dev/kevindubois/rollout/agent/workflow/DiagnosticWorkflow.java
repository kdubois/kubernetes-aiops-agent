package dev.kevindubois.rollout.agent.workflow;

import dev.kevindubois.rollout.agent.agents.DiagnosticAgent;
import dev.kevindubois.rollout.agent.agents.MetricsDiagnosticAgent;
import dev.langchain4j.agentic.declarative.ParallelAgent;

public interface DiagnosticWorkflow {

    @ParallelAgent(
        description = "Gathers logs and application metrics in parallel from stable and canary pods",
        outputKey = "diagnosticData",
        subAgents = {
            DiagnosticAgent.class,
            MetricsDiagnosticAgent.class
        }
    )
    String gatherAllDiagnostics(String message);
}