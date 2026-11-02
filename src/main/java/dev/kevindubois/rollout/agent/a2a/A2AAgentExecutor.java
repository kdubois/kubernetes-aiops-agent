package dev.kevindubois.rollout.agent.a2a;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import dev.kevindubois.rollout.agent.analysis.AnalysisOutcome;
import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.analysis.AnalysisService;
import dev.kevindubois.rollout.agent.analysis.PauseGate;
import dev.kevindubois.rollout.agent.observability.ActivityEvents;

/**
 * A2A protocol bridge. Extracts message content and metadata, delegates to
 * AnalysisService, and formats the result as an A2A task artifact.
 *
 * <p>execute() runs the analysis synchronously and completes the task with a
 * single artifact. Cancellation is not supported: the analysis is a short
 * read-only run, so the interface default (TaskNotCancelableError) applies.
 */
@ApplicationScoped
public class A2AAgentExecutor {

    @Inject
    AnalysisService analysisService;

    @Inject
    ActivityEvents activityEvents;

    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {
            @Override
            public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
                Log.info("A2A: Processing request");
                activityEvents.requestStarted("A2A request");

                emitter.startWork();

                try {
                    Message message = context.getMessage();
                    String messageContent = extractMessageContent(message);
                    Map<String, Object> metadata = message != null ? message.metadata() : null;
                    String repoUrl = extractMetadataValue(metadata, "repoUrl");
                    String baseBranch = extractMetadataValue(metadata, "baseBranch", "main");
                    String namespace = extractMetadataValue(metadata, "namespace", "default");
                    String rolloutName = extractMetadataValue(metadata, "rolloutName");
                    String stableSelector = extractMetadataValue(metadata, "stableSelector", "role=stable");
                    String canarySelector = extractMetadataValue(metadata, "canarySelector", "role=canary");
                    PauseGate pauseGate = PauseGate.fromMap(metadata);

                    AnalysisOutcome outcome = analysisService.analyze(
                            messageContent, namespace, stableSelector, canarySelector,
                            rolloutName, repoUrl, baseBranch, pauseGate);

                    emitter.addArtifact(List.of(new TextPart(formatAnalysisResult(outcome))),
                            null, "analysis", null);
                    emitter.complete();

                    Log.info("A2A: Request completed successfully");

                } catch (Exception e) {
                    Log.error("A2A: Error processing request", e);
                    activityEvents.requestFailed(e.getMessage());
                    emitter.fail(agentMessage("Error: " + e.getMessage()));
                }
            }
        };
    }

    private Message agentMessage(String text) {
        return Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart(text)))
                .build();
    }

    private String extractMetadataValue(Map<String, Object> metadata, String key) {
        return extractMetadataValue(metadata, key, null);
    }

    private String extractMetadataValue(Map<String, Object> metadata, String key, String defaultValue) {
        if (metadata == null) return defaultValue;
        Object value = metadata.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private String extractMessageContent(Message message) {
        if (message == null || message.parts() == null) return "";

        return message.parts().stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }

    private String formatAnalysisResult(AnalysisOutcome outcome) {
        AnalysisResult result = outcome.result();
        StringBuilder sb = new StringBuilder();
        sb.append("## Analysis Result\n\n");
        sb.append("**Decision:** ").append(result.promote() ? "PROMOTE" : "ROLLBACK").append("\n");
        sb.append("**Confidence:** ").append(result.confidence()).append("%\n\n");

        if (outcome.pausedRollout() != null) {
            sb.append("**Paused:** rollout `").append(outcome.pausedRollout())
                    .append("` is paused for human review (confidence below threshold)\n\n");
        }

        if (result.analysis() != null && !result.analysis().isEmpty()) {
            sb.append("### Analysis\n").append(result.analysis()).append("\n\n");
        }

        if (result.rootCause() != null && !result.rootCause().isEmpty()) {
            sb.append("### Root Cause\n").append(result.rootCause()).append("\n\n");
        }

        if (result.remediation() != null && !result.remediation().isEmpty()) {
            sb.append("### Remediation\n").append(result.remediation()).append("\n\n");
        }

        return sb.toString();
    }
}
