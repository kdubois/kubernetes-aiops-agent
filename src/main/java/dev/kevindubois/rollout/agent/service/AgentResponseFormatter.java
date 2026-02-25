package dev.kevindubois.rollout.agent.service;

import dev.kevindubois.rollout.agent.model.KubernetesAgentResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service responsible for formatting agent responses for display.
 * Converts structured responses into human-readable formats.
 */
@ApplicationScoped
public class AgentResponseFormatter {
    
    /**
     * Format the response in a structured way for the client
     */
    public String format(KubernetesAgentResponse response) {
        StringBuilder formatted = new StringBuilder();
        
        formatted.append("# Kubernetes Analysis\n\n");
        formatted.append(response.analysis()).append("\n\n");
        
        formatted.append("## Summary\n\n");
        formatted.append("- **Root Cause**: ").append(response.rootCause()).append("\n");
        formatted.append("- **Remediation**: ").append(response.remediation()).append("\n");
        formatted.append("- **Recommendation**: ").append(response.promote() ? "Promote canary" : "Do not promote canary").append("\n");
        formatted.append("- **Confidence**: ").append(response.confidence()).append("%\n");
        
        if (response.prLink() != null) {
            formatted.append("- **PR Link**: ").append(response.prLink()).append("\n");
        }
        
        return formatted.toString();
    }
}

