package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.model.ActivityEventStore;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@Unremovable
public class ActivityEventListener implements AgentListener {

    @Inject
    ActivityEventStore activityEvents;

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        String name = request.agentName();
        String message = switch (name) {
            case "KubernetesWorkflow" -> "Analysis request received";
            case "ParallelDataWorkflow" -> "Fetching rollout data";
            case "DiagnosticsDataAgent" -> "Gathering pod logs";
            case "MetricsDataAgent" -> "Collecting pod metrics";
            case "DataCombinerAgent" -> "Preparing diagnostic report";
            case "AnalysisLoop" -> "Starting AI analysis loop";
            case "AnalysisAgent" -> "Analyzing rollout health";
            case "ScoringAgent" -> "Scoring confidence";
            default -> null;
        };

        if (message != null) {
            activityEvents.publish("AGENT_START", message);
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        String name = response.agentName();
        Object output = response.output();

        if (output instanceof AnalysisResult result) {
            String recommendation = result.promote() ? "PROMOTE" : "ROLLBACK";

            String summary = extractSummary(result.analysis());
            if (summary != null) {
                activityEvents.publish("ANALYSIS_SUMMARY", "Analysis summary", summary);
            }

            String insight = result.analysis();
            if (insight != null && insight.length() > 200) {
                insight = insight.substring(0, 200) + "...";
            }
            activityEvents.publish("ANALYSIS_INSIGHT", insight,
                    "Root cause: " + (result.rootCause() != null ? result.rootCause() : "No issues"));
            activityEvents.publish("DECISION",
                    recommendation + " recommended", "confidence: " + result.confidence() + "%");

        } else if (output instanceof ScoringResult result) {
            activityEvents.publish("CONFIDENCE_SCORE",
                    "Score: " + result.score() + "/100", result.reason());
            if (result.needsRetry()) {
                activityEvents.publish("RETRY",
                        "Retrying analysis", result.reason());
            }

        } else if (output instanceof String logOutput) {
            if ("ParallelDataWorkflow".equals(name)) {
                activityEvents.publish("ANALYSIS_SUMMARY", "Logs and metrics gathered");
            }

        } else if ("KubernetesWorkflow".equals(name)) {
            activityEvents.publish("ANALYSIS_SUMMARY", "Analysis complete");
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        String message = error.error() != null ? error.error().getMessage() : "Unknown error";
        Log.errorf("Agent %s failed: %s", error.agentName(), message);
        activityEvents.publish("ERROR", error.agentName() + " failed", message);
    }

    private String extractSummary(String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return null;
        }
        String firstSentence = analysis.split("[.!?]\\s", 2)[0].trim();
        if (firstSentence.length() > 150) {
            firstSentence = firstSentence.substring(0, 147) + "...";
        }
        if (!firstSentence.endsWith(".") && !firstSentence.endsWith("!") && !firstSentence.endsWith("?")) {
            firstSentence += ".";
        }
        return firstSentence;
    }
}
