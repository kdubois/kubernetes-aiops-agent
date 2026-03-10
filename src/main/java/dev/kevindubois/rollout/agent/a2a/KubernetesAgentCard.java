package dev.kevindubois.rollout.agent.a2a;

import java.util.List;

import io.a2a.spec.AgentInterface;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.a2a.server.PublicAgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;

/**
 * A2A Agent Card configuration for Kubernetes Agent.
 * Defines the agent's capabilities, skills, and endpoints for A2A protocol discovery.
 */
@ApplicationScoped
public class KubernetesAgentCard {

    @Inject
    @ConfigProperty(name = "a2a.agent.url", defaultValue = "http://localhost")
    String agentUrl;

    @Inject
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int serverPort;
    
    @Inject
    @ConfigProperty(name = "a2a.agent.version", defaultValue = "1.0.1")
    String agentVersion;
    
    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        String baseUrl = agentUrl + ":" + serverPort;
        
        return new AgentCard.Builder()
                .name("Kubernetes Agent")
                .description("Expert Kubernetes SRE agent for canary deployment analysis. " +
                           "Analyzes metrics, logs, and pod health to determine deployment safety. " +
                           "Provides automated remediation via GitHub PRs when issues are detected.")
                .url(baseUrl + "/")
                .version(agentVersion)
                .protocolVersion("1.0.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(
                    new AgentSkill.Builder()
                        .id("kubernetes-analysis")
                        .name("Kubernetes Canary Analysis")
                        .description("Analyzes canary deployments using multi-agent workflow: " +
                                   "diagnostic data gathering, analysis, and automated remediation")
                        .tags(List.of("kubernetes", "canary", "analysis", "sre", "remediation"))
                        .build()
                ))
                .preferredTransport("jsonrpc")
                .additionalInterfaces(List.of(
                    new AgentInterface("jsonrpc", baseUrl + "/a2a")
                ))
                .build();
    }
}