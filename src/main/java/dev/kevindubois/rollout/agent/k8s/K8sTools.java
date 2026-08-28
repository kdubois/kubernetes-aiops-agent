package dev.kevindubois.rollout.agent.k8s;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.kevindubois.rollout.agent.model.ActivityEventStore;

import java.util.*;

/**
 * Kubernetes tools for gathering canary vs stable diagnostics and metrics.
 *
 * @Unremovable is required because this bean is accessed via programmatic lookup
 * in DiagnosticsDataAgent and MetricsDataAgent.
 */
@ApplicationScoped
@Unremovable
public class K8sTools {

    @Inject
    KubernetesClient k8sClient;

    @Inject
    ActivityEventStore activityEvents;

    public Map<String, Object> getCanaryMetrics(String namespace) {
        Log.info("=== Executing: getCanaryMetrics ===");

        if (namespace == null || namespace.isEmpty()) {
            return Map.of("error", "namespace is required");
        }

        Log.infof("Getting canary metrics for namespace: %s", namespace);

        try {
            Map<String, Object> result = new HashMap<>();
            result.put("namespace", namespace);

            List<Pod> stablePods = k8sClient.pods().inNamespace(namespace)
                    .withLabels(Map.of("role", "stable")).list().getItems();
            List<Pod> canaryPods = k8sClient.pods().inNamespace(namespace)
                    .withLabels(Map.of("role", "canary")).list().getItems();

            if (!stablePods.isEmpty()) {
                activityEvents.publish("TOOL_CALL", "Fetching stable pod metrics",
                    "pod=" + stablePods.get(0).getMetadata().getName()
                    + " (selected 1 of " + stablePods.size() + " stable pods)");
            }
            if (!canaryPods.isEmpty()) {
                activityEvents.publish("TOOL_CALL", "Fetching canary pod metrics",
                    "pod=" + canaryPods.get(0).getMetadata().getName()
                    + " (selected 1 of " + canaryPods.size() + " canary pods)");
            }

            Map<String, Object> stableMetrics;
            if (!stablePods.isEmpty()) {
                Pod stablePod = stablePods.get(0);
                stableMetrics = fetchApplicationMetricsInternal(namespace, stablePod.getMetadata().getName(), "/q/metrics", 8080);
                stableMetrics.put("podName", stablePod.getMetadata().getName());
            } else {
                stableMetrics = new HashMap<>(Map.of("error", "No stable pods found"));
            }

            Map<String, Object> canaryMetrics;
            if (!canaryPods.isEmpty()) {
                Pod canaryPod = canaryPods.get(0);
                canaryMetrics = fetchApplicationMetricsInternal(namespace, canaryPod.getMetadata().getName(), "/q/metrics", 8080);
                canaryMetrics.put("podName", canaryPod.getMetadata().getName());
            } else {
                canaryMetrics = new HashMap<>(Map.of("error", "No canary pods found"));
            }

            result.put("stable", stableMetrics);
            result.put("canary", canaryMetrics);

            if (!stableMetrics.containsKey("error")) {
                activityEvents.publish("TOOL_RESULT", "Stable pod metrics retrieved",
                    "pod=" + stableMetrics.get("podName"));
            }
            if (!canaryMetrics.containsKey("error")) {
                activityEvents.publish("TOOL_RESULT", "Canary pod metrics retrieved",
                    "pod=" + canaryMetrics.get("podName"));
            }

            Log.info("Successfully retrieved canary metrics");
            return result;

        } catch (Exception e) {
            Log.error("Error getting canary metrics", e);
            return Map.of("error", e.getMessage());
        }
    }

    public Map<String, Object> getCanaryDiagnostics(String namespace, String containerName, Integer tailLines) {
        Log.info("=== Executing: getCanaryDiagnostics ===");

        if (namespace == null || namespace.isEmpty()) {
            return Map.of("error", "namespace is required");
        }

        int lines = (tailLines != null && tailLines > 0) ? tailLines : 200;
        Log.infof("Getting canary diagnostics for namespace: %s, container: %s, lines: %d",
                namespace, containerName, lines);

        try {
            Map<String, Object> result = new HashMap<>();
            result.put("namespace", namespace);

            List<Pod> stablePods = k8sClient.pods().inNamespace(namespace)
                    .withLabels(Map.of("role", "stable")).list().getItems();
            List<Pod> canaryPods = k8sClient.pods().inNamespace(namespace)
                    .withLabels(Map.of("role", "canary")).list().getItems();

            if (!stablePods.isEmpty()) {
                activityEvents.publish("TOOL_CALL", "Fetching stable pod logs",
                    "pod=" + stablePods.get(0).getMetadata().getName()
                    + " (selected 1 of " + stablePods.size() + " stable pods)");
            }
            if (!canaryPods.isEmpty()) {
                activityEvents.publish("TOOL_CALL", "Fetching canary pod logs",
                    "pod=" + canaryPods.get(0).getMetadata().getName()
                    + " (selected 1 of " + canaryPods.size() + " canary pods)");
            }

            Map<String, Object> stableData = buildPodDiagnostics(stablePods, namespace, containerName, lines);
            Map<String, Object> canaryData = buildPodDiagnostics(canaryPods, namespace, containerName, lines);

            result.put("stable", stableData);
            result.put("canary", canaryData);

            if (stableData.containsKey("podName")) {
                activityEvents.publish("TOOL_RESULT", "Stable pod logs retrieved",
                    "pod=" + stableData.get("podName") + ", status=" + stableData.get("phase"));
            }
            if (canaryData.containsKey("podName")) {
                activityEvents.publish("TOOL_RESULT", "Canary pod logs retrieved",
                    "pod=" + canaryData.get("podName") + ", status=" + canaryData.get("phase"));
            }

            Log.info("Successfully retrieved canary diagnostics");
            return result;

        } catch (Exception e) {
            Log.error("Error getting canary diagnostics", e);
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> buildPodDiagnostics(List<Pod> pods, String namespace, String containerName, int lines) {
        Map<String, Object> info = new HashMap<>();
        if (pods.isEmpty()) {
            info.put("error", "No pods found");
            return info;
        }

        Pod pod = pods.get(0);
        info.put("podName", pod.getMetadata().getName());
        info.put("phase", pod.getStatus().getPhase());
        info.put("podCount", pods.size());

        if (pod.getStatus().getContainerStatuses() != null) {
            long readyCount = pod.getStatus().getContainerStatuses().stream()
                .filter(ContainerStatus::getReady)
                .count();
            info.put("readyContainers", readyCount + "/" + pod.getStatus().getContainerStatuses().size());
        }

        String actualContainerName = (containerName != null && !containerName.isEmpty()) ? containerName : null;
        Map<String, Object> logsResult = getLogsInternal(namespace, pod.getMetadata().getName(), actualContainerName, false, lines);
        if (logsResult.containsKey("logs")) {
            String rawLogs = (String) logsResult.get("logs");
            String dedupedLogs = deduplicateLogs(rawLogs, 30);
            if (dedupedLogs.length() > 3000) {
                dedupedLogs = dedupedLogs.substring(0, 3000) + "\n... (truncated for brevity)";
            }
            info.put("logs", dedupedLogs);
        } else if (logsResult.containsKey("error")) {
            info.put("logsError", logsResult.get("error"));
        }

        return info;
    }

    private Map<String, Object> getLogsInternal(String namespace, String podName, String containerName, Boolean previous, Integer tailLines) {
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required");
        }

        boolean getPrevious = previous != null && previous;
        int lines = (tailLines != null && tailLines > 0) ? tailLines : 200;

        try {
            Pod pod = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();

            if (pod == null) {
                return Map.of("error", "Pod not found: " + namespace + "/" + podName);
            }

            List<Container> containers = pod.getSpec().getContainers();
            String targetContainer = resolveContainer(containers, containerName);

            var podResource = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName);

            String logs;
            if (targetContainer != null && !targetContainer.isEmpty()) {
                logs = podResource.inContainer(targetContainer).tailingLines(lines).getLog(getPrevious);
            } else {
                logs = podResource.tailingLines(lines).getLog(getPrevious);
            }

            if (logs == null) {
                logs = "(no logs available)";
            }

            return Map.of(
                "namespace", namespace,
                "podName", podName,
                "container", targetContainer != null ? targetContainer : "default",
                "previous", getPrevious,
                "logs", logs
            );

        } catch (Exception e) {
            Log.error("Error getting logs", e);
            return Map.of("error", e.getMessage());
        }
    }

    private String resolveContainer(List<Container> containers, String containerName) {
        if (containerName != null && !containerName.isEmpty()) {
            boolean exists = containers.stream().anyMatch(c -> c.getName().equals(containerName));
            if (exists) return containerName;
        }
        if (containers == null || containers.isEmpty()) return null;
        if (containers.size() == 1) return containers.get(0).getName();
        return containers.stream()
                .filter(c -> !c.getName().contains("proxy") &&
                           !c.getName().contains("envoy") &&
                           !c.getName().contains("sidecar"))
                .findFirst()
                .map(Container::getName)
                .orElse(containers.get(0).getName());
    }

    private Map<String, Object> fetchApplicationMetricsInternal(String namespace, String podName, String metricsPath, int port) {
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required");
        }

        String path = (metricsPath != null && !metricsPath.isEmpty()) ? metricsPath : "/q/metrics";

        try {
            Pod pod = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();

            if (pod == null) {
                return Map.of("error", "Pod not found: " + namespace + "/" + podName);
            }

            String podIP = pod.getStatus().getPodIP();
            if (podIP == null || podIP.isEmpty()) {
                return Map.of("error", "Pod IP not available - pod may not be running");
            }

            String metricsUrl = "http://" + podIP + ":" + port + path;
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(metricsUrl))
                .timeout(java.time.Duration.ofSeconds(10))
                .GET()
                .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Map.of("error", "Failed to fetch metrics", "statusCode", response.statusCode());
            }

            Map<String, Object> parsedMetrics = parsePrometheusMetrics(response.body());
            parsedMetrics.put("namespace", namespace);
            parsedMetrics.put("podName", podName);
            parsedMetrics.put("podIP", podIP);
            return parsedMetrics;

        } catch (java.io.IOException | InterruptedException e) {
            Log.error("Error fetching metrics from pod", e);
            return Map.of("error", "Failed to connect to pod metrics endpoint", "details", e.getMessage());
        } catch (Exception e) {
            Log.error("Error fetching application metrics", e);
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> parsePrometheusMetrics(String metricsText) {
        Map<String, Object> metrics = new HashMap<>();

        String[] lines = metricsText.split("\n");
        for (String line : lines) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;

            try {
                int spaceIndex = line.lastIndexOf(' ');
                if (spaceIndex > 0) {
                    String metricPart = line.substring(0, spaceIndex);
                    String value = line.substring(spaceIndex + 1);

                    if (metricPart.startsWith("http_requests_total")) {
                        metrics.put("totalRequests", Double.parseDouble(value));
                    } else if (metricPart.startsWith("http_requests_success_total")) {
                        metrics.put("successfulRequests", Double.parseDouble(value));
                    } else if (metricPart.startsWith("http_requests_error_total")) {
                        metrics.put("errorRequests", Double.parseDouble(value));
                    } else if (metricPart.startsWith("http_requests_success_rate")) {
                        metrics.put("successRate", Double.parseDouble(value) * 100);
                    } else if (metricPart.contains("http_request_duration_seconds") && metricPart.contains("quantile=\"0.5\"")) {
                        metrics.put("latencyP50Ms", Double.parseDouble(value) * 1000);
                    } else if (metricPart.contains("http_request_duration_seconds") && metricPart.contains("quantile=\"0.95\"")) {
                        metrics.put("latencyP95Ms", Double.parseDouble(value) * 1000);
                    } else if (metricPart.contains("http_request_duration_seconds") && metricPart.contains("quantile=\"0.99\"")) {
                        metrics.put("latencyP99Ms", Double.parseDouble(value) * 1000);
                    } else if (metricPart.contains("http_request_duration_seconds_sum")) {
                        metrics.put("latencySumSeconds", Double.parseDouble(value));
                    } else if (metricPart.contains("http_request_duration_seconds_count")) {
                        metrics.put("latencyCount", Double.parseDouble(value));
                    } else if (metricPart.startsWith("app_version_info") && metricPart.contains("version=\"")) {
                        int versionStart = metricPart.indexOf("version=\"") + 9;
                        int versionEnd = metricPart.indexOf("\"", versionStart);
                        if (versionEnd > versionStart) {
                            metrics.put("version", metricPart.substring(versionStart, versionEnd));
                        }
                        if (metricPart.contains("scenario=\"")) {
                            int scenarioStart = metricPart.indexOf("scenario=\"") + 10;
                            int scenarioEnd = metricPart.indexOf("\"", scenarioStart);
                            if (scenarioEnd > scenarioStart) {
                                metrics.put("scenario", metricPart.substring(scenarioStart, scenarioEnd));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.debug("Failed to parse metric line: " + line, e);
            }
        }

        if (metrics.containsKey("totalRequests") && metrics.containsKey("successfulRequests")) {
            double total = (Double) metrics.get("totalRequests");
            double successful = (Double) metrics.get("successfulRequests");
            if (total > 0) {
                metrics.put("calculatedSuccessRate", (successful / total) * 100);
                metrics.put("errorRate", ((total - successful) / total) * 100);
            }
        }

        if (metrics.containsKey("latencySumSeconds") && metrics.containsKey("latencyCount")) {
            double sum = (Double) metrics.get("latencySumSeconds");
            double count = (Double) metrics.get("latencyCount");
            if (count > 0) {
                metrics.put("latencyMeanMs", (sum / count) * 1000);
            }
        }

        return metrics;
    }

    private String deduplicateLogs(String logs, int maxLines) {
        if (logs == null || logs.isEmpty()) return logs;

        String[] lines = logs.split("\n");
        if (lines.length <= maxLines) return logs;

        StringBuilder result = new StringBuilder();
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        String lastError = null;
        int consecutiveCount = 0;

        for (String line : lines) {
            if (line.contains("ERROR") || line.contains("Exception") || line.contains("WARN")) {
                String errorPattern = line.replaceAll("^\\d{2}:\\d{2}:\\d{2}", "")
                                         .replaceAll("\\(pool-\\d+-thread-\\d+\\)", "(thread)")
                                         .trim();

                if (errorPattern.equals(lastError)) {
                    consecutiveCount++;
                } else {
                    if (lastError != null && consecutiveCount > 1) {
                        result.append("  [Previous error repeated ").append(consecutiveCount).append(" times]\n");
                    }
                    result.append(line).append("\n");
                    lastError = errorPattern;
                    consecutiveCount = 1;
                }
                errorCounts.merge(errorPattern, 1, Integer::sum);
            } else {
                if (consecutiveCount > 1 && lastError != null) {
                    result.append("  [Previous error repeated ").append(consecutiveCount).append(" times]\n");
                    consecutiveCount = 0;
                    lastError = null;
                }
                if (line.contains("INFO") || line.contains("WARN") ||
                    line.contains("started") || line.contains("milestone")) {
                    result.append(line).append("\n");
                }
            }
        }

        if (consecutiveCount > 1 && lastError != null) {
            result.append("  [Previous error repeated ").append(consecutiveCount).append(" times]\n");
        }

        if (!errorCounts.isEmpty()) {
            result.append("\n=== Error Summary ===\n");
            errorCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .forEach(entry -> {
                    String shortError = entry.getKey().length() > 100
                        ? entry.getKey().substring(0, 100) + "..."
                        : entry.getKey();
                    result.append(String.format("- %dx: %s\n", entry.getValue(), shortError));
                });
        }

        return result.toString();
    }
}
