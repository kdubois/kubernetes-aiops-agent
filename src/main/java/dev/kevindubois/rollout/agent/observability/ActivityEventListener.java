package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.analysis.ScoringResult;
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
            case "AnalysisDataAgent" -> "Fetching rollout data";
            case "DiagnosticsDataAgent" -> "Gathering pod logs";
            case "RolloutStatusDataAgent" -> "Reading rollout status";
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

        if (output instanceof ScoringResult result) {
            if ("ScoringAgent".equals(name)) {
                activityEvents.publish("CONFIDENCE_SCORE",
                        "Score: " + result.score() + "/100", result.reason());
                if (result.needsRetry()) {
                    activityEvents.publish("RETRY",
                            "Retrying analysis", result.reason());
                }
            }

        } else if (output instanceof String) {
            if ("AnalysisDataAgent".equals(name)) {
                activityEvents.publish("ANALYSIS_SUMMARY", "Logs and metrics gathered");
            }
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        String message = error.error() != null ? error.error().getMessage() : "Unknown error";
        Log.errorf("Agent %s failed: %s", error.agentName(), message);
        // the A2A remediation call runs on a background thread inside
        // RemediationDelegator, which reports its own outcome events — errors
        // surfacing here are analysis-agent failures
        activityEvents.publish("ERROR", error.agentName() + " failed", message);
    }
}
