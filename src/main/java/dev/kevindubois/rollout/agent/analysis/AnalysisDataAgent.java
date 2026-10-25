package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.declarative.ParallelAgent;

/**
 * Parallel workflow that fetches diagnostics, rollout status, and metrics simultaneously
 * using non-AI agents. All agents call K8sTools directly — no LLM involved in data gathering.
 */
public interface AnalysisDataAgent {

    @ParallelAgent(
        description = "Fetches pod diagnostics, rollout status, and application metrics in parallel",
        outputKey = "diagnosticData",
        subAgents = {
            DiagnosticsDataAgent.class,
            RolloutStatusDataAgent.class,
            MetricsDataAgent.class
        }
    )
    String fetchData(String message, String namespace);
}
