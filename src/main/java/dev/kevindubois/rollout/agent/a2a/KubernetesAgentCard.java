package dev.kevindubois.rollout.agent.a2a;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;

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

        return AgentCard.builder()
                .name("Kubernetes Agent")
                .description("Expert Kubernetes SRE agent for canary deployment analysis. " +
                           "Analyzes metrics, logs, and pod health to determine deployment safety. " +
                           "Provides automated remediation via GitHub PRs when issues are detected.")
                .version(agentVersion)
                .capabilities(AgentCapabilities.builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(
                    AgentSkill.builder()
                        .id("kubernetes-analysis")
                        .name("Kubernetes Canary Analysis")
                        .description("Analyzes canary deployments using multi-agent workflow: " +
                                   "diagnostic data gathering, analysis, and automated remediation")
                        .tags(List.of("kubernetes", "canary", "analysis", "sre", "remediation"))
                        .build()
                ))
                .supportedInterfaces(List.of(
                    new AgentInterface(TransportProtocol.JSONRPC.asString(), baseUrl)
                ))
                .build();
    }
}
