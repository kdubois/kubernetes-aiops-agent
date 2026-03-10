package dev.kevindubois.rollout.agent.a2a;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.a2a.spec.Task;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

import dev.kevindubois.rollout.agent.workflow.KubernetesWorkflow;
import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.utils.RetryHelper;
import dev.kevindubois.rollout.agent.utils.ToolCallLimiter;

/**
 * A2A framework integration for the KubernetesAgent.
 * Simplified implementation using Quarkus LangChain4j patterns.
 *
 * This executor bridges the A2A protocol with the LangChain4j-based
 * KubernetesWorkflow, handling message extraction, context management,
 * and response formatting.
 */
@ApplicationScoped
public class A2AAgentExecutor {

    @Inject
    KubernetesWorkflow workflow;

    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {
            @Override
            public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                Log.info("A2A: Processing request");
                
                TaskUpdater updater = new TaskUpdater(context, eventQueue);
                if (context.getTask() == null) {
                    updater.submit();
                }
                updater.startWork();

                try {
                    // Extract request parameters
                    String messageContent = extractMessageContent(context.getMessage());
                    String memoryId = extractMemoryId(context);
                    Map<String, String> metadata = extractMetadata(context.getMessage());
                    
                    String repoUrl = metadata.get("repoUrl");
                    String baseBranch = metadata.getOrDefault("baseBranch", "main");
                    
                    Log.debug(MessageFormat.format("Memory ID: {0}, RepoUrl: {1}, Branch: {2}",
                        memoryId, repoUrl, baseBranch));
                    
                    // Reset tool call limiter for new session
                    ToolCallLimiter.resetSession(memoryId);
                    
                    // Execute workflow with retry logic
                    AnalysisResult result = RetryHelper.executeWithRetryOnTransientErrors(
                        () -> workflow.execute(memoryId, messageContent, repoUrl, baseBranch),
                        "A2A workflow execution"
                    );
                    
                    // Return formatted response
                    String response = formatAnalysisResult(result);
                    updater.addArtifact(List.of(new TextPart(response, null)), null, null, null);
                    updater.complete();
                    
                    Log.info("A2A: Request completed successfully");
                    
                } catch (Exception e) {
                    Log.error("A2A: Error processing request", e);
                    String errorMessage = "Error: " + e.getMessage();
                    updater.addArtifact(List.of(new TextPart(errorMessage, null)), null, null, null);
                    updater.complete();
                }
            }

            @Override
            public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                Task task = context.getTask();
                if (task.getStatus().state() == TaskState.CANCELED ||
                    task.getStatus().state() == TaskState.COMPLETED) {
                    throw new TaskNotCancelableError();
                }
                new TaskUpdater(context, eventQueue).cancel();
            }
            
            /**
             * Extract memory ID with priority: memoryId > userId > sessionId > taskId > default
             */
            private String extractMemoryId(RequestContext context) {
                Message message = context.getMessage();
                if (message.getMetadata() != null) {
                    Object memoryId = message.getMetadata().get("memoryId");
                    if (memoryId != null) return memoryId.toString();
                    
                    Object userId = message.getMetadata().get("userId");
                    if (userId != null) return userId.toString();
                    
                    Object sessionId = message.getMetadata().get("sessionId");
                    if (sessionId != null) return sessionId.toString();
                }
                
                if (context.getTask() != null) {
                    Log.warn("Using task ID as memory ID - conversation history will not persist");
                    return context.getTask().getId();
                }
                
                return "default";
            }
            
            /**
             * Extract all metadata as a map
             */
            private Map<String, String> extractMetadata(Message message) {
                if (message.getMetadata() == null) {
                    return Map.of();
                }
                return message.getMetadata().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey(),
                        e -> e.getValue() != null ? e.getValue().toString() : ""
                    ));
            }
            
            /**
             * Extract text content from message parts
             */
            private String extractMessageContent(Message message) {
                if (message.getParts() == null) {
                    return "";
                }
                
                return message.getParts().stream()
                    .filter(part -> part instanceof TextPart)
                    .map(part -> ((TextPart) part).getText())
                    .reduce("", (a, b) -> a + "\n" + b)
                    .trim();
            }
            
            /**
             * Format analysis result as markdown
             */
            private String formatAnalysisResult(AnalysisResult result) {
                StringBuilder sb = new StringBuilder();
                sb.append("## Analysis Result\n\n");
                sb.append("**Decision:** ").append(result.promote() ? "✅ PROMOTE" : "❌ ROLLBACK").append("\n");
                sb.append("**Confidence:** ").append(result.confidence()).append("%\n\n");
                
                if (result.analysis() != null && !result.analysis().isEmpty()) {
                    sb.append("### Analysis\n").append(result.analysis()).append("\n\n");
                }
                
                if (result.rootCause() != null && !result.rootCause().isEmpty()) {
                    sb.append("### Root Cause\n").append(result.rootCause()).append("\n\n");
                }
                
                if (result.remediation() != null && !result.remediation().isEmpty()) {
                    sb.append("### Remediation\n").append(result.remediation()).append("\n\n");
                }
                
                if (result.prLink() != null && !result.prLink().isEmpty()) {
                    sb.append("### Pull Request\n").append(result.prLink()).append("\n");
                }
                
                return sb.toString();
            }
        };
    }
}

