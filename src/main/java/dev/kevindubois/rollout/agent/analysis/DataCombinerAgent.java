package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.Agent;
import io.quarkus.logging.Log;

/**
 * Non-AI agent that combines the parallel diagnostic, rollout status, and metrics reports
 * into a single diagnosticData string for the AnalysisAgent to consume.
 */
public class DataCombinerAgent {

    @Agent(description = "Combines diagnostic, rollout status, and metrics reports", outputKey = "diagnosticData")
    public static String combineReports(String diagnosticReport, String rolloutReport, String metricsReport) {
        String combined = diagnosticReport + "\n\n" + rolloutReport + "\n\n" + metricsReport;

        Log.info("DataCombinerAgent: combined report (" + combined.length() + " chars)");
        return combined;
    }
}
