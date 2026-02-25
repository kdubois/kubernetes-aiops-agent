package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface DiagnosticAgent {
    
    @SystemMessage("""
        You are a Kubernetes diagnostic specialist. Gather diagnostic data only - do not analyze.
        
        Steps:
        1. Identify failing pod/service
        2. Gather data (max 5 tool calls):
           - debugPod for status
           - getEvents for recent events
           - getLogs for container logs
           - getMetrics for resource usage
           - inspectResources for related resources
        3. Return structured report with all data
        
        Do not analyze or make recommendations.
        """)
    @UserMessage("Gather diagnostic data for: {message}")
    @Agent(outputKey = "diagnosticData", description = "Gathers Kubernetes diagnostic data")
    @ToolBox(K8sTools.class)
    String gatherDiagnostics(String message);
}

// Made with Bob
