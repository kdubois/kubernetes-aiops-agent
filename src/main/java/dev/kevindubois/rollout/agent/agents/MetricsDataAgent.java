package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-AI agent that fetches application metrics from /q/metrics endpoints for stable and canary pods.
 * Calls K8sTools directly — no LLM involved.
 */
public class MetricsDataAgent {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("namespace[=:\\s]+(\\S+)");

    @Agent(description = "Fetches application metrics for stable and canary pods", outputKey = "metricsReport")
    public static String gatherMetrics(String message) {
        Log.info("MetricsDataAgent: fetching application metrics (non-AI agent)");

        K8sTools k8sTools = Arc.container().instance(K8sTools.class).get();
        String namespace = extractNamespace(message);

        Map<String, Object> metrics = k8sTools.getCanaryMetrics(namespace);

        String report = formatReport(metrics);
        Log.info("MetricsDataAgent: report generated (" + report.length() + " chars)");
        return report;
    }

    @SuppressWarnings("unchecked")
    private static String formatReport(Map<String, Object> metrics) {
        if (metrics.containsKey("error")) {
            return "=== METRICS REPORT ===\nERROR: " + metrics.get("error") + "\n=== END ===";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== METRICS REPORT ===\n");

        Map<String, Object> stable = (Map<String, Object>) metrics.get("stable");
        Map<String, Object> canary = (Map<String, Object>) metrics.get("canary");

        formatMetricsSection(sb, "STABLE", stable);
        formatMetricsSection(sb, "CANARY", canary);

        sb.append("=== END ===");
        return sb.toString();
    }

    private static void formatMetricsSection(StringBuilder sb, String label, Map<String, Object> metricsData) {
        if (metricsData == null || metricsData.containsKey("error")) {
            sb.append(label).append(" METRICS: ").append(metricsData != null ? metricsData.get("error") : "No data").append("\n");
            return;
        }

        sb.append(label).append(" METRICS: ");
        sb.append("totalRequests=").append(formatNumber(metricsData.get("totalRequests")));
        sb.append(", successRate=").append(formatPercent(metricsData.get("calculatedSuccessRate"), metricsData.get("successRate")));
        sb.append(", errorRate=").append(formatPercent(metricsData.get("errorRate"), null));
        sb.append(", p95=").append(formatMs(metricsData.get("latencyP95Ms")));
        sb.append(", p99=").append(formatMs(metricsData.get("latencyP99Ms")));
        sb.append("\n");
    }

    private static String formatNumber(Object value) {
        if (value == null) return "N/A";
        if (value instanceof Double d) return String.format("%.0f", d);
        return value.toString();
    }

    private static String formatPercent(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null) return "N/A";
        if (value instanceof Double d) return String.format("%.2f%%", d);
        return value + "%";
    }

    private static String formatMs(Object value) {
        if (value == null) return "N/A";
        if (value instanceof Double d) return String.format("%.1fms", d);
        return value + "ms";
    }

    static String extractNamespace(String message) {
        if (message == null) return "default";
        Matcher matcher = NAMESPACE_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "default";
    }
}
