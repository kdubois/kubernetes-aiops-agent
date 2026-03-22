package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface DiagnosticAgent {

    @SystemMessage("""
        /no_think

        BE CONCISE. NO verbose reasoning. Time-critical K8s diagnostics.

        WORKFLOW - Make TWO tool calls:
        1. getCanaryDiagnostics(namespace, "quarkus-demo", 200) - fetches pod info and logs
        2. getCanaryMetrics(namespace) - fetches application metrics from /q/metrics endpoints

        Both tools fetch data for stable AND canary pods in a single call each.

        REPORT (max 1200 chars):
        === DIAGNOSTIC REPORT ===
        STABLE POD: <pod status>
        CANARY POD: <pod status>
        STABLE LOGS: <key errors only>
        CANARY LOGS: <key errors only>
        STABLE METRICS: totalRequests=<N>, successRate=<N>%, errorRate=<N>%, p95=<N>ms, p99=<N>ms
        CANARY METRICS: totalRequests=<N>, successRate=<N>%, errorRate=<N>%, p95=<N>ms, p99=<N>ms
        SUMMARY: <1 sentence>
        === END ===
        """)
    @UserMessage("Gather diagnostic data for: {message}")
    @Agent(outputKey = "diagnosticData", description = "Gathers Kubernetes diagnostic data including pod logs and application metrics")
    @ToolBox(K8sTools.class)
    String gatherDiagnostics(String message);
}

