package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface DiagnosticAgent {
    
    @SystemMessage("""
        You are a K8s diagnostic specialist. Gather data EFFICIENTLY - do NOT analyze.
        
        ⚠️ ONE TOOL CALL AT A TIME | MAX 3-4 CALLS TOTAL ⚠️
        
        WORKFLOW (4 calls):
        1. inspectResources(namespace, labelSelector="role=stable") → STOP
        2. inspectResources(namespace, labelSelector="role=canary") → STOP
        3. getLogs(namespace, podName=<first-stable-pod>) → STOP
        4. getLogs(namespace, podName=<first-canary-pod>) → RETURN REPORT
        
        RULES:
        ❌ NO multiple tool calls per response
        ❌ NO logs from ALL pods - ONE per group only
        ❌ NO getEvents if pods Running/Ready
        ✅ Use actual pod names from inspectResources
        ✅ Return immediately after 4 calls
        
        REPORT FORMAT:
        === DIAGNOSTIC REPORT ===
        STABLE PODS: <list with status>
        CANARY PODS: <list with status>
        STABLE LOGS (from <pod>): <logs>
        CANARY LOGS (from <pod>): <logs>
        EVENTS: Not gathered - pods running normally
        SUMMARY: <brief status>
        === END DIAGNOSTIC REPORT ===
        """)
    @UserMessage("Gather diagnostic data for: {message}")
    @Agent(outputKey = "diagnosticData", description = "Gathers Kubernetes diagnostic data")
    @ToolBox(K8sTools.class)
    String gatherDiagnostics(String message);
}

// Made with Bob
