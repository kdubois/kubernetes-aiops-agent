package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.kevindubois.rollout.agent.utils.TextUtils;
import dev.langchain4j.agentic.Agent;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;

import java.util.Map;

/**
 * Non-AI agent that fetches pod diagnostic data (status + logs) for stable and canary pods.
 * Calls K8sTools directly — no LLM involved.
 */
public class DiagnosticsDataAgent {

    @Agent(description = "Fetches pod info and logs for stable and canary pods", outputKey = "diagnosticReport")
    public static String gatherDiagnostics(String message) {
        Log.info("DiagnosticsDataAgent: fetching pod diagnostics (non-AI agent)");

        K8sTools k8sTools = Arc.container().instance(K8sTools.class).get();
        String namespace = TextUtils.extractNamespace(message);

        Map<String, Object> diagnostics = k8sTools.getCanaryDiagnostics(namespace, null, 200);

        String report = formatReport(diagnostics);
        Log.infof("DiagnosticsDataAgent: report generated (%d chars)", report.length());
        return report;
    }

    @SuppressWarnings("unchecked")
    private static String formatReport(Map<String, Object> diagnostics) {
        if (diagnostics.containsKey("error")) {
            return "=== LOG DIAGNOSTIC REPORT ===\nERROR: " + diagnostics.get("error") + "\n=== END ===";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== LOG DIAGNOSTIC REPORT ===\n");

        Map<String, Object> stable = (Map<String, Object>) diagnostics.get("stable");
        Map<String, Object> canary = (Map<String, Object>) diagnostics.get("canary");

        formatPodSection(sb, "STABLE", stable);
        formatPodSection(sb, "CANARY", canary);

        sb.append("=== END ===");
        return sb.toString();
    }

    private static void formatPodSection(StringBuilder sb, String label, Map<String, Object> podData) {
        if (podData == null || podData.containsKey("error")) {
            sb.append(label).append(" POD: ").append(podData != null ? podData.get("error") : "No data").append("\n");
            return;
        }

        sb.append(label).append(" POD: ")
            .append(podData.getOrDefault("podName", "unknown"))
            .append(" - ").append(podData.getOrDefault("phase", "unknown"))
            .append(" - Ready: ").append(podData.getOrDefault("readyContainers", "unknown"))
            .append("\n");

        Object logs = podData.get("logs");
        if (logs != null) {
            sb.append(label).append(" LOGS:\n").append(logs).append("\n");
        } else {
            Object logsError = podData.get("logsError");
            sb.append(label).append(" LOGS: ").append(logsError != null ? logsError : "No logs available").append("\n");
        }
    }
}
