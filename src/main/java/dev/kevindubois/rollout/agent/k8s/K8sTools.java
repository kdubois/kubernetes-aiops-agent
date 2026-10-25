package dev.kevindubois.rollout.agent.k8s;

import dev.kevindubois.rollout.agent.observability.ActivityEventStore;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    /**
     * Canary weight per step index, used as a fallback when the controller
     * does not report live traffic weights. Matches the demo rollout's steps:
     * 10% (steps 0-3), 60% (steps 4-5), 100% (steps 6-7).
     */
    private static final int[] STEP_WEIGHTS = {10, 10, 10, 10, 60, 60, 100, 100};

    @Inject
    KubernetesClient k8sClient;

    @Inject
    ActivityEventStore activityEvents;

    /**
     * Fetches the Argo Rollouts resource status for a namespace: phase, current step,
     * traffic split, and stable/canary pod counts. When no rollout name is given,
     * the first rollout in the namespace is used.
     */
    public RolloutStatusData getRolloutStatus(String namespace, String rolloutName) {
        Log.info("=== Executing: getRolloutStatus ===");

        if (namespace == null || namespace.isEmpty()) {
            return RolloutStatusData.error("namespace is required");
        }

        try {
            Rollout rollout = findRollout(namespace, rolloutName);
            if (rollout == null) {
                if (rolloutName != null && !rolloutName.isBlank()) {
                    return RolloutStatusData.error("Rollout '" + rolloutName + "' not found in namespace " + namespace);
                }
                return RolloutStatusData.error("No Rollout found in namespace " + namespace);
            }

            RolloutStatus status = rollout.getStatus();
            String name = rollout.getMetadata().getName();
            String phase = (status != null && status.getPhase() != null) ? status.getPhase() : "Unknown";
            int canaryWeight = status != null ? getCanaryWeight(status) : 0;
            Integer currentStepIndex = status != null ? status.getCurrentStepIndex() : null;

            List<Pod>[] pods = fetchPodsPairInParallel(namespace);
            long stablePodCount = countActivePods(pods[0]);
            long canaryPodCount = countActivePods(pods[1]);

            activityEvents.publish("TOOL_RESULT", "Rollout status retrieved",
                    "rollout=" + name + ", phase=" + phase + ", canaryWeight=" + canaryWeight + "%");

            Log.infof("Retrieved rollout status for %s/%s: phase=%s, canaryWeight=%d%%",
                    namespace, name, phase, canaryWeight);
            return new RolloutStatusData(null, name, namespace, phase, currentStepIndex,
                    canaryWeight, 100 - canaryWeight, stablePodCount, canaryPodCount);

        } catch (Exception e) {
            Log.error("Error getting rollout status", e);
            return RolloutStatusData.error(e.getMessage());
        }
    }

    /**
     * Pauses the rollout in the namespace so a human can review before promotion
     * continues. Argo Rollouts holds the current step until the rollout is unpaused.
     *
     * @return the paused rollout name, or null if no rollout was found or the patch failed
     */
    public String pauseRollout(String namespace) {
        Log.info("=== Executing: pauseRollout ===");

        if (namespace == null || namespace.isEmpty()) {
            Log.warn("pauseRollout: namespace is required");
            return null;
        }

        try {
            Rollout rollout = findRollout(namespace, null);
            if (rollout == null) {
                Log.warnf("pauseRollout: no Rollout found in namespace %s", namespace);
                return null;
            }

            String name = rollout.getMetadata().getName();
            // JSON merge patch: sets only spec.paused and leaves the rest of the spec untouched
            k8sClient.resources(Rollout.class)
                    .inNamespace(namespace)
                    .withName(name)
                    .patch(PatchContext.of(PatchType.JSON_MERGE), "{\"spec\":{\"paused\":true}}");

            activityEvents.publish("PAUSE", "Rollout paused for human review", "rollout=" + name);
            Log.infof("Paused rollout %s/%s pending human review", namespace, name);
            return name;

        } catch (Exception e) {
            Log.error("Error pausing rollout", e);
            return null;
        }
    }

    private Rollout findRollout(String namespace, String rolloutName) {
        var rollouts = k8sClient.resources(Rollout.class).inNamespace(namespace);
        if (rolloutName != null && !rolloutName.isBlank()) {
            return rollouts.withName(rolloutName).get();
        }
        List<Rollout> items = rollouts.list().getItems();
        if (items.size() > 1) {
            Log.warnf("Multiple Rollouts in namespace %s, using %s", namespace, items.get(0).getMetadata().getName());
        }
        return items.isEmpty() ? null : items.get(0);
    }

    private long countActivePods(List<Pod> pods) {
        return pods.stream()
                .filter(p -> p.getMetadata() != null && p.getMetadata().getDeletionTimestamp() == null)
                .count();
    }

    private int getCanaryWeight(RolloutStatus status) {
        String phase = status.getPhase() != null ? status.getPhase() : "Unknown";

        if ("Degraded".equals(phase) || "Aborted".equals(phase)) {
            return 0;
        }

        // When a rollout finishes, stableRS and canaryRS converge to the same hash.
        if ("Healthy".equals(phase)
                && status.getStableRS() != null
                && status.getStableRS().equals(status.getCanaryRS())) {
            return 0;
        }

        // Prefer the live traffic weight reported by the controller.
        if (status.getCanary() != null) {
            CanaryWeights weights = status.getCanary().getWeights();
            if (weights != null && weights.getCanary() != null) {
                return weights.getCanary().getWeight();
            }
        }

        // Fall back to the step-index table when live weights are unavailable.
        Integer stepIndex = status.getCurrentStepIndex();
        if (stepIndex != null && stepIndex >= 0 && stepIndex < STEP_WEIGHTS.length) {
            return STEP_WEIGHTS[stepIndex];
        }

        return 0;
    }

    public PodDataResult getCanaryMetrics(String namespace) {
        Log.info("=== Executing: getCanaryMetrics ===");

        if (namespace == null || namespace.isEmpty()) {
            return PodDataResult.error("namespace is required");
        }

        Log.infof("Getting canary metrics for namespace: %s", namespace);

        try {
            List<Pod>[] pods = fetchPodsPairInParallel(namespace);
            List<Pod> stablePods = pods[0];
            List<Pod> canaryPods = pods[1];

            var stableMetricsHolder = new HashMap<String, Object>();
            var canaryMetricsHolder = new HashMap<String, Object>();

            Thread stableThread = Thread.ofVirtual().start(() -> {
                if (!stablePods.isEmpty()) {
                    Pod pod = stablePods.get(0);
                    stableMetricsHolder.putAll(fetchApplicationMetricsInternal(namespace, pod.getMetadata().getName(), "/q/metrics", 8080));
                    stableMetricsHolder.put("podName", pod.getMetadata().getName());
                } else {
                    stableMetricsHolder.put("error", "No stable pods found");
                }
            });
            Thread canaryThread = Thread.ofVirtual().start(() -> {
                if (!canaryPods.isEmpty()) {
                    Pod pod = canaryPods.get(0);
                    canaryMetricsHolder.putAll(fetchApplicationMetricsInternal(namespace, pod.getMetadata().getName(), "/q/metrics", 8080));
                    canaryMetricsHolder.put("podName", pod.getMetadata().getName());
                } else {
                    canaryMetricsHolder.put("error", "No canary pods found");
                }
            });
            stableThread.join();
            canaryThread.join();

            if (!stableMetricsHolder.containsKey("error")) {
                activityEvents.publish("TOOL_RESULT", "Stable pod metrics retrieved",
                    "pod=" + stableMetricsHolder.get("podName"));
            }
            if (!canaryMetricsHolder.containsKey("error")) {
                activityEvents.publish("TOOL_RESULT", "Canary pod metrics retrieved",
                    "pod=" + canaryMetricsHolder.get("podName"));
            }

            Log.info("Successfully retrieved canary metrics");
            return PodDataResult.of(stableMetricsHolder, canaryMetricsHolder);

        } catch (Exception e) {
            Log.error("Error getting canary metrics", e);
            return PodDataResult.error(e.getMessage());
        }
    }

    public PodDataResult getCanaryDiagnostics(String namespace, String containerName, Integer tailLines) {
        Log.info("=== Executing: getCanaryDiagnostics ===");

        if (namespace == null || namespace.isEmpty()) {
            return PodDataResult.error("namespace is required");
        }

        int lines = (tailLines != null && tailLines > 0) ? tailLines : 200;
        Log.infof("Getting canary diagnostics for namespace: %s, container: %s, lines: %d",
                namespace, containerName, lines);

        try {
            List<Pod>[] pods = fetchPodsPairInParallel(namespace);
            List<Pod> stablePods = pods[0];
            List<Pod> canaryPods = pods[1];

            var stableHolder = new HashMap<String, Object>();
            var canaryHolder = new HashMap<String, Object>();

            Thread stableThread = Thread.ofVirtual().start(() ->
                    stableHolder.putAll(buildPodDiagnostics(stablePods, namespace, containerName, lines)));
            Thread canaryThread = Thread.ofVirtual().start(() ->
                    canaryHolder.putAll(buildPodDiagnostics(canaryPods, namespace, containerName, lines)));
            stableThread.join();
            canaryThread.join();

            if (stableHolder.containsKey("podName")) {
                activityEvents.publish("TOOL_RESULT", "Stable pod logs retrieved",
                    "pod=" + stableHolder.get("podName") + ", status=" + stableHolder.get("phase"));
            }
            if (canaryHolder.containsKey("podName")) {
                activityEvents.publish("TOOL_RESULT", "Canary pod logs retrieved",
                    "pod=" + canaryHolder.get("podName") + ", status=" + canaryHolder.get("phase"));
            }

            Log.info("Successfully retrieved canary diagnostics");
            return PodDataResult.of(stableHolder, canaryHolder);

        } catch (Exception e) {
            Log.error("Error getting canary diagnostics", e);
            return PodDataResult.error(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Pod>[] fetchPodsPairInParallel(String namespace) throws InterruptedException {
        var stable = new ArrayList<Pod>();
        var canary = new ArrayList<Pod>();

        Thread t1 = Thread.ofVirtual().start(() ->
                stable.addAll(k8sClient.pods().inNamespace(namespace)
                        .withLabels(Map.of("role", "stable")).list().getItems()));
        Thread t2 = Thread.ofVirtual().start(() ->
                canary.addAll(k8sClient.pods().inNamespace(namespace)
                        .withLabels(Map.of("role", "canary")).list().getItems()));
        t1.join();
        t2.join();

        return new List[]{stable, canary};
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
