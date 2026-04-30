package dev.kevindubois.rollout.agent.observability;

import dev.kevindubois.rollout.agent.model.ActivityEventStore;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
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
            case "DiagnosticAgent" -> "Gathering pod info and application logs";
            case "MetricsDiagnosticAgent" -> "Collecting application metrics from pods";
            case "AnalysisAgent" -> "Evaluating metrics and logs for anomalies";
            case "ScoringAgent" -> "Evaluating analysis quality and confidence";
            default -> name + " starting";
        };
        activityEvents.publish("AGENT_START", message, name);
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        String name = response.agentName();
        Object output = response.output();

        if (output instanceof AnalysisResult result) {
            String recommendation = result.promote() ? "PROMOTE" : "ROLLBACK";
            String insight = result.analysis();
            if (insight != null && insight.length() > 200) {
                insight = insight.substring(0, 200) + "...";
            }
            activityEvents.publish("ANALYSIS_INSIGHT", insight,
                    "Root cause: " + (result.rootCause() != null ? result.rootCause() : "None identified"));
            activityEvents.publish("AGENT_COMPLETE",
                    recommendation + " recommended (confidence: " + result.confidence() + "%)", name);

        } else if (output instanceof ScoringResult result) {
            activityEvents.publish("CONFIDENCE_SCORE",
                    "Score: " + result.score() + "/100", result.reason());
            if (result.needsRetry()) {
                activityEvents.publish("RETRY",
                        "Retrying analysis — confidence too low", result.reason());
            }
            activityEvents.publish("AGENT_COMPLETE",
                    "Scoring complete — " + (result.needsRetry() ? "retry needed" : "quality acceptable"), name);

        } else if (output instanceof String diagnosticOutput) {
            String summary = diagnosticOutput;
            if (summary.length() > 150) {
                summary = summary.substring(0, 150) + "...";
            }
            activityEvents.publish("AGENT_COMPLETE", "Diagnostics gathered", summary);

        } else {
            activityEvents.publish("AGENT_COMPLETE", name + " completed");
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        String message = error.error() != null ? error.error().getMessage() : "Unknown error";
        Log.errorf("Agent %s failed: %s", error.agentName(), message);
        activityEvents.publish("ERROR", error.agentName() + " failed", message);
    }
}
