package dev.kevindubois.rollout.agent.agents;

import dev.langchain4j.agentic.Agent;
import io.quarkus.logging.Log;

/**
 * Non-AI agent that combines the parallel diagnostic and metrics reports into a single
 * diagnosticData string for the AnalysisAgent to consume.
 */
public class DataCombinerAgent {

    @Agent(description = "Combines diagnostic and metrics reports", outputKey = "diagnosticData")
    public static String combineReports(String diagnosticReport, String metricsReport) {
        String combined = diagnosticReport + "\n\n" + metricsReport;

        Log.info("DataCombinerAgent: combined report (" + combined.length() + " chars)");
        return combined;
    }
}
