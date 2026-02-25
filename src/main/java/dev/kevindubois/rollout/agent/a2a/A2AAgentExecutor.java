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

import dev.kevindubois.rollout.agent.workflow.KubernetesWorkflow;
import dev.kevindubois.rollout.agent.model.AnalysisResult;

/**
 * A2A framework integration for the KubernetesAgent.
 * Handles the integration between the A2A framework and the KubernetesAgent.
 * Processes requests from A2A clients and passes them to the Quarkus LangChain4j AI service.
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
                Log.info("A2AAgentExecutor: Processing A2A request");
                
                // Create task updater to manage the A2A task lifecycle
                TaskUpdater updater = new TaskUpdater(context, eventQueue);
                if (context.getTask() == null) {
                    updater.submit();
                }
                updater.startWork();

                try {
                    // Extract message content from the request
                    String messageContent = extractMessageContent(context.getMessage());
                    Log.debug(MessageFormat.format("Extracted message content: {0}", messageContent));
                    
                    // Extract persistent memory ID from message metadata or use task ID as fallback
                    String memoryId = extractMemoryId(context);
                    Log.debug(MessageFormat.format("Using memory ID: {0}", memoryId));
                    
                    // Execute the workflow
                    AnalysisResult result = workflow.execute(memoryId, messageContent);
                    Log.info("Workflow executed successfully");
                    
                    // Format the response
                    String formattedResponse = formatAnalysisResult(result);
                    TextPart responsePart = new TextPart(formattedResponse, null);
                    List<Part<?>> parts = List.of(responsePart);
                    updater.addArtifact(parts, null, null, null);
                    updater.complete();
                    
                } catch (Exception e) {
                    Log.error("Error processing KubernetesAgent request", e);
                    
                    // Handle error and return error response
                    String errorMessage = "Error processing Kubernetes analysis request: " + e.getMessage();
                    TextPart errorPart = new TextPart(errorMessage, null);
                    List<Part<?>> parts = List.of(errorPart);
                    updater.addArtifact(parts, null, null, null);
                    updater.complete();
                }
            }

            @Override
            public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError { 
                Task task = context.getTask();

                if (task.getStatus().state() == TaskState.CANCELED) {
                    // task already cancelled
                    throw new TaskNotCancelableError();
                }

                if (task.getStatus().state() == TaskState.COMPLETED) {
                    // task already completed
                    throw new TaskNotCancelableError();
                }

                // cancel the task
                TaskUpdater updater = new TaskUpdater(context, eventQueue);
                updater.cancel();
            }
            
            /**
             * Extract persistent memory ID from the request context.
             * Priority order:
             * 1. "memoryId" from message metadata
             * 2. "userId" from message metadata
             * 3. "sessionId" from message metadata
             * 4. Task ID (fallback for backward compatibility)
             * 5. "default" (last resort)
             */
            private String extractMemoryId(RequestContext context) {
                Message message = context.getMessage();
                
                // Try to extract from message metadata
                if (message.getMetadata() != null) {
                    // First priority: explicit memoryId
                    Object memoryId = message.getMetadata().get("memoryId");
                    if (memoryId != null) {
                        Log.debug(MessageFormat.format("Using memoryId from metadata: {0}", memoryId));
                        return memoryId.toString();
                    }
                    
                    // Second priority: userId
                    Object userId = message.getMetadata().get("userId");
                    if (userId != null) {
                        Log.debug(MessageFormat.format("Using userId from metadata as memoryId: {0}", userId));
                        return userId.toString();
                    }
                    
                    // Third priority: sessionId
                    Object sessionId = message.getMetadata().get("sessionId");
                    if (sessionId != null) {
                        Log.debug(MessageFormat.format("Using sessionId from metadata as memoryId: {0}", sessionId));
                        return sessionId.toString();
                    }
                }
                
                // Fallback to task ID for backward compatibility
                if (context.getTask() != null) {
                    String taskId = context.getTask().getId();
                    Log.warn(MessageFormat.format("No persistent identifier found in metadata, falling back to task ID: {0}. " +
                        "This will NOT maintain conversation history across requests.", taskId));
                    return taskId;
                }
                
                // Last resort
                Log.warn("No memory identifier found, using 'default'. Conversation history will be shared across all sessions.");
                return "default";
            }
            
            /**
             * Extract message content from the A2A message
             */
            private String extractMessageContent(Message message) {
                StringBuilder content = new StringBuilder();
                
                if (message.getParts() != null) {
                    for (Part<?> part : message.getParts()) {
                        if (part instanceof TextPart textPart) {
                            Log.debug(MessageFormat.format("Processing text part: {0}", textPart.getText()));
                            content.append(textPart.getText()).append("\n");
                        }
                    }
                }
                
                return content.toString().trim();
            }
            
            /**
             * Format AnalysisResult into a readable response
             */
            private String formatAnalysisResult(AnalysisResult result) {
                StringBuilder response = new StringBuilder();
                
                response.append("## Analysis Result\n\n");
                response.append("**Decision:** ").append(result.promote() ? "PROMOTE" : "ROLLBACK").append("\n");
                response.append("**Confidence:** ").append(result.confidence()).append("%\n\n");
                
                if (result.analysis() != null && !result.analysis().isEmpty()) {
                    response.append("### Analysis\n").append(result.analysis()).append("\n\n");
                }
                
                if (result.rootCause() != null && !result.rootCause().isEmpty()) {
                    response.append("### Root Cause\n").append(result.rootCause()).append("\n\n");
                }
                
                if (result.remediation() != null && !result.remediation().isEmpty()) {
                    response.append("### Remediation\n").append(result.remediation()).append("\n\n");
                }
                
                if (result.prLink() != null && !result.prLink().isEmpty()) {
                    response.append("### Pull Request\n").append(result.prLink()).append("\n");
                }
                
                return response.toString();
            }
        };
    }
}

