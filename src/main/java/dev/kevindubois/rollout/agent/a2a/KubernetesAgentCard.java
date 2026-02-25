package dev.kevindubois.rollout.agent.a2a;

import java.util.Collections;
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

@ApplicationScoped
public class KubernetesAgentCard {

    @Inject
    @ConfigProperty(name = "url", defaultValue = "http://localhost")
    String url;

    @Inject
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int serverPort;
    
    @Inject
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "kubernetes-agent")
    String applicationName;
    
    @Inject
    @ConfigProperty(name = "agent.version", defaultValue = "1.0.0")
    String agentVersion;
    
    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        String baseUrl = url + ":" + serverPort;
        
        return new AgentCard.Builder()
                .name("Kubernetes Agent")
                .description("An expert Kubernetes SRE specializing in canary deployment analysis. It analyzes the provided metrics and logs to determine if a canary deployment is healthy.")
                .url(baseUrl + "/")
                .version(agentVersion)
                .protocolVersion("1.0.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false) // Set to false until streaming is implemented
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(Collections.singletonList("text"))
                .defaultOutputModes(Collections.singletonList("text"))
                .skills(Collections.singletonList(new AgentSkill.Builder()
                                .id("kubernetes-analysis")
                                .name("Kubernetes Analysis")
                                .description("Analyzes canary deployment logs and metrics to determine if a canary deployment is healthy.")
                                .tags(List.of("analysis", "kubernetes", "canary"))
                                .build()))
                .preferredTransport("jsonrpc")
                .additionalInterfaces(List.of(
                        new AgentInterface("jsonrpc", baseUrl + "/a2a")))
                .build();
    }
}