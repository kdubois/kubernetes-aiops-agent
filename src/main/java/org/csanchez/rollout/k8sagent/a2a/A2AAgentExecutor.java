package org.csanchez.rollout.k8sagent.a2a;

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

import org.csanchez.rollout.k8sagent.agents.KubernetesAgent;
import org.csanchez.rollout.k8sagent.model.KubernetesAgentResponse;
import org.csanchez.rollout.k8sagent.service.AgentResponseFormatter;
import org.csanchez.rollout.k8sagent.service.AgentResponseParser;

/**
 * A2A framework integration for the KubernetesAgent.
 * Handles the integration between the A2A framework and the KubernetesAgent.
 * Processes requests from A2A clients and passes them to the Quarkus LangChain4j AI service.
 */
@ApplicationScoped
public class A2AAgentExecutor {

    @Inject
    KubernetesAgent kubernetesAgent;
    
    @Inject
    AgentResponseParser responseParser;
    
    @Inject
    AgentResponseFormatter responseFormatter;

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
                    
                    // Use task ID as memory ID to maintain conversation history per task
                    String memoryId = context.getTask() != null ? context.getTask().getId() : "default";
                    Log.debug(MessageFormat.format("Using memory ID: {0}", memoryId));
                    
                    // Process the request using the KubernetesAgent with memory support
                    String agentResponse = kubernetesAgent.chat(memoryId, messageContent);
                    Log.info("KubernetesAgent processed request successfully");
                    
                    // Parse the agent response into a structured format
                    KubernetesAgentResponse parsedResponse = responseParser.parse(agentResponse);
                    Log.debug(MessageFormat.format("Parsed response: {0}", parsedResponse));
                    
                    // Return the result as a TextPart with structured information
                    String formattedResponse = responseFormatter.format(parsedResponse);
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
        };
    }
}

