package dev.kevindubois.rollout.agent.remediation.a2a;

import dev.langchain4j.agentic.AgenticServices;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the LangChain4j A2A client agent for the remote remediation agent.
 *
 * <p>Construction is lazy because the builder fetches the agent card over
 * HTTP — the remediation agent does not need to be up when this service
 * starts, only when a remediation is actually triggered.
 */
@ApplicationScoped
public class RemediationA2AAgentProducer {

    @ConfigProperty(name = "remediation.agent.url", defaultValue = "http://remediation-agent:8080")
    String url;

    private volatile RemediationA2AAgent agent;

    public RemediationA2AAgent agent() {
        RemediationA2AAgent current = agent;
        if (current == null) {
            synchronized (this) {
                current = agent;
                if (current == null) {
                    Log.infof("Building A2A client agent for %s", url);
                    current = AgenticServices.a2aBuilder(url, RemediationA2AAgent.class).build();
                    agent = current;
                }
            }
        }
        return current;
    }
}
