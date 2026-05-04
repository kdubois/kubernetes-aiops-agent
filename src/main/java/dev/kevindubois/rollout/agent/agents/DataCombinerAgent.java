package dev.kevindubois.rollout.agent.agents;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.quarkus.logging.Log;

/**
 * Non-AI agent that combines the parallel diagnostic and metrics reports into a single
 * diagnosticData string for the AnalysisAgent to consume.
 */
public class DataCombinerAgent {

    @Agent(description = "Combines diagnostic and metrics reports", outputKey = "diagnosticData")
    public static String combineReports(AgenticScope scope) {
        String diagnostics = (String) scope.readState("diagnosticReport", "");
        String metrics = (String) scope.readState("metricsReport", "");

        String combined = diagnostics + "\n\n" + metrics;
        scope.writeState("diagnosticData", combined);

        Log.info("DataCombinerAgent: combined report (" + combined.length() + " chars)");
        return combined;
    }
}
