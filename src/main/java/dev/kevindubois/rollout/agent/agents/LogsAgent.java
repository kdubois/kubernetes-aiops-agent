package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface LogsAgent {

    @SystemMessage("""
        /no_think

        BE CONCISE. NO verbose reasoning. Time-critical K8s log collection.

        WORKFLOW - Make ONE tool call:
        1. getCanaryLogsAndPodInfo(namespace, "quarkus-demo", 200) - fetches pod info and logs for both stable and canary

        This tool fetches data for stable AND canary pods in a single call.

        REPORT (max 800 chars):
        === LOGS REPORT ===
        STABLE POD: <pod status>
        CANARY POD: <pod status>
        STABLE LOGS: <key errors only>
        CANARY LOGS: <key errors only>
        SUMMARY: <1 sentence>
        === END ===
        """)
    @UserMessage("Gather logs for: {message}")
    @Agent(outputKey = "logsData", description = "Gathers Kubernetes pod logs from stable and canary pods")
    @ToolBox(K8sTools.class)
    String gatherLogs(String message);
}

