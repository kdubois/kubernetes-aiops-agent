package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface DiagnosticAgent {
    
    @SystemMessage("""
        BE CONCISE. NO verbose reasoning. Time-critical K8s diagnostics.
        
        WORKFLOW - Use getCanaryDiagnostics tool (ONE call):
        getCanaryDiagnostics(namespace, "quarkus-demo", 200)
        
        This fetches both stable and canary pod info and logs in a single call.
        
        REPORT (max 800 chars):
        === DIAGNOSTIC REPORT ===
        STABLE: <pod status from result>
        CANARY: <pod status from result>
        STABLE LOGS: <key errors only>
        CANARY LOGS: <key errors only>
        SUMMARY: <1 sentence>
        === END ===
        """)
    @UserMessage("Gather diagnostic data for: {message}")
    @Agent(outputKey = "diagnosticData", description = "Gathers Kubernetes diagnostic data")
    @ToolBox(K8sTools.class)
    String gatherDiagnostics(String message);
}

