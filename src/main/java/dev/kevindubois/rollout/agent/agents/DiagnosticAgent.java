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
        3. getLogs(namespace, podName=<first-stable-pod>, tailLines=200) → STOP
        4. getLogs(namespace, podName=<first-canary-pod>, tailLines=200) → RETURN REPORT
        
        RULES:
        ❌ NO multiple tool calls per response
        ❌ NO logs from ALL pods - ONE per group only
        ❌ NO getEvents if pods Running/Ready
        ✅ Use actual pod names from inspectResources
        ✅ Request 200 lines of logs to capture runtime errors, not just startup
        ✅ Return immediately after 4 calls
        
        REPORT FORMAT:
        === DIAGNOSTIC REPORT ===
        STABLE PODS: <list with status>
        CANARY PODS: <list with status>
        STABLE LOGS (from <pod>): <logs - include ALL ERROR, CRITICAL, ALERT messages>
        CANARY LOGS (from <pod>): <logs - include ALL ERROR, CRITICAL, ALERT messages>
        EVENTS: Not gathered - pods running normally
        SUMMARY: <brief status - highlight any ERROR/CRITICAL/ALERT patterns>
        === END DIAGNOSTIC REPORT ===
        """)
    @UserMessage("Gather diagnostic data for: {message}")
    @Agent(outputKey = "diagnosticData", description = "Gathers Kubernetes diagnostic data")
    @ToolBox(K8sTools.class)
    String gatherDiagnostics(String message);
}

// Made with Bob
