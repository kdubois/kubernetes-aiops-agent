package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.model.ActivityEventStore;
import dev.kevindubois.rollout.agent.model.RemediationResult;
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
            case "ParallelDataWorkflow" -> "Fetching rollout data";
            case "DiagnosticsDataAgent" -> "Gathering pod logs";
            case "MetricsDataAgent" -> "Collecting pod metrics";
            case "DataCombinerAgent" -> "Preparing diagnostic report";
            case "AnalysisLoop" -> "Starting AI analysis loop";
            case "AnalysisAgent" -> "Analyzing rollout health";
            case "ScoringAgent" -> "Scoring confidence";
            case "RemediationAgent" -> "Creating remediation PR/Issue";
            case "RemediationLoop" -> "Starting remediation loop";
            case "RemediationScoringAgent" -> "Scoring remediation quality";
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
            } else if ("RemediationScoringAgent".equals(name)) {
                activityEvents.publish("CONFIDENCE_SCORE",
                        "Remediation score: " + result.score() + "/100", result.reason());
                if (result.needsRetry()) {
                    activityEvents.publish("RETRY",
                            "Retrying remediation", result.reason());
                }
            }

        } else if (output instanceof RemediationResult result) {
            if ("RemediationAgent".equals(name)) {
                if (result.prLink() != null && !result.prLink().isEmpty()) {
                    activityEvents.publish("REMEDIATION", "GitHub artifact created", result.prLink());
                } else {
                    activityEvents.publish("REMEDIATION", "Remediation completed", "No GitHub artifact created");
                }
            }

        } else if (output instanceof String) {
            if ("ParallelDataWorkflow".equals(name)) {
                activityEvents.publish("ANALYSIS_SUMMARY", "Logs and metrics gathered");
            }
        } else if ("RemediationAgent".equals(name) && output == null) {
            activityEvents.publish("REMEDIATION", "Failed", "Agent returned null");
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        String message = error.error() != null ? error.error().getMessage() : "Unknown error";
        Log.errorf("Agent %s failed: %s", error.agentName(), message);
        activityEvents.publish("ERROR", error.agentName() + " failed", message);
    }
}
