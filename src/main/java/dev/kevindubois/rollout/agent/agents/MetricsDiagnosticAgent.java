package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface MetricsDiagnosticAgent {

    @SystemMessage("""
        /no_think

        BE CONCISE. NO verbose reasoning. Time-critical metrics collection.

        WORKFLOW - Use getCanaryMetrics tool (ONE call):
        getCanaryMetrics(namespace)

        This fetches application metrics from the /q/metrics endpoint of both
        stable and canary pods in a single call, returning error rates, success
        rates, latency percentiles, and request counts.

        REPORT (max 800 chars):
        === METRICS REPORT ===
        STABLE METRICS: totalRequests=<N>, successRate=<N>%, errorRate=<N>%, p95=<N>ms, p99=<N>ms
        CANARY METRICS: totalRequests=<N>, successRate=<N>%, errorRate=<N>%, p95=<N>ms, p99=<N>ms
        COMPARISON: <1 sentence comparing canary vs stable>
        === END ===
        """)
    @UserMessage("Gather application metrics for: {message}")
    @Agent(outputKey = "metricsDiagnosticData", description = "Gathers application metrics from /q/metrics endpoints of stable and canary pods")
    @ToolBox(K8sTools.class)
    String gatherMetrics(String message);
}