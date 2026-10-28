package dev.kevindubois.rollout.agent.remediation.a2a;

/**
 * Typed client for the remote remediation A2A agent, built programmatically
 * via {@code AgenticServices.a2aBuilder} (see {@link RemediationA2AAgentProducer}).
 * The request travels as the JSON serialization of {@link RemediationRequest}
 * in the message text; the task completes with the JSON
 * {@link RemediationResponse} as its artifact, which arrives as the method's
 * return value once the sandbox run finishes.
 *
 * <p>Deliberately not annotated with {@code @Agent}: the Quarkus agentic
 * extension would then validate the method parameters against AgenticScope
 * output keys, which does not apply to a directly-invoked A2A client.
 */
public interface RemediationA2AAgent {

    String remediate(String requestJson);
}
