package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.declarative.ParallelAgent;

/**
 * Parallel workflow that fetches diagnostics and metrics simultaneously using non-AI agents.
 * Both agents call K8sTools directly — no LLM involved in data gathering.
 */
public interface AnalysisDataAgent {

    @ParallelAgent(
        description = "Fetches pod diagnostics and application metrics in parallel",
        outputKey = "diagnosticData",
        subAgents = {
            DiagnosticsDataAgent.class,
            MetricsDataAgent.class
        }
    )
    String fetchData(String message, String namespace);
}
