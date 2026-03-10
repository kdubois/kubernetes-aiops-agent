package dev.kevindubois.rollout.agent.k8s;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Kubernetes tools for LangChain4j
 */
@ApplicationScoped
public class K8sTools {
    
    @Inject
    KubernetesClient k8sClient;
    
    /**
     * Debug a Kubernetes pod to get detailed information about its status and conditions
     * @param namespace The Kubernetes namespace where the pod is located (e.g., 'default', 'kube-system')
     * @param podName The exact name of the pod to debug (e.g., 'my-app-7d8f9c5b6-xyz12')
     */
    @Tool("Debug a Kubernetes pod to get detailed information about its status and conditions")
    public Map<String, Object> debugPod(String namespace, String podName) {
        Log.info("=== Executing Tool: debugPod ===");
        
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required and cannot be empty");
        }
        Log.info(MessageFormat.format("Debugging pod: {0}/{1}", namespace, podName));
        
        
        try {
            Pod pod = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();
            
            if (pod == null) {
                return Map.of("error", MessageFormat.format("Pod not found: {0}/{1}", namespace, podName));
            }
            
            PodStatus status = pod.getStatus();
            
            Map<String, Object> debugInfo = new HashMap<>();
            debugInfo.put("podName", podName);
            debugInfo.put("namespace", namespace);
            debugInfo.put("phase", status.getPhase());
            debugInfo.put("reason", status.getReason());
            debugInfo.put("message", status.getMessage());
            debugInfo.put("hostIP", status.getHostIP());
            debugInfo.put("podIP", status.getPodIP());
            debugInfo.put("startTime", status.getStartTime());
            
            // Pod conditions
            List<Map<String, Object>> conditions = status.getConditions().stream()
                .map(c -> {
                    Map<String, Object> condition = new HashMap<>();
                    condition.put("type", c.getType());
                    condition.put("status", c.getStatus());
                    condition.put("reason", c.getReason() != null ? c.getReason() : "");
                    condition.put("message", c.getMessage() != null ? c.getMessage() : "");
                    condition.put("lastTransitionTime", c.getLastTransitionTime() != null ? c.getLastTransitionTime() : "");
                    return condition;
                })
                .collect(Collectors.toList());
            debugInfo.put("conditions", conditions);
            
            // Container statuses
            List<Map<String, Object>> containerStatuses = new ArrayList<>();
            if (status.getContainerStatuses() != null) {
                for (ContainerStatus cs : status.getContainerStatuses()) {
                    Map<String, Object> containerInfo = new HashMap<>();
                    containerInfo.put("name", cs.getName());
                    containerInfo.put("ready", cs.getReady());
                    containerInfo.put("restartCount", cs.getRestartCount());
                    containerInfo.put("image", cs.getImage());
                    
                    ContainerState state = cs.getState();
                    if (state.getRunning() != null) {
                        containerInfo.put("state", "Running");
                        containerInfo.put("startedAt", state.getRunning().getStartedAt());
                    } else if (state.getWaiting() != null) {
                        containerInfo.put("state", "Waiting");
                        containerInfo.put("reason", state.getWaiting().getReason());
                        containerInfo.put("message", state.getWaiting().getMessage());
                    } else if (state.getTerminated() != null) {
                        containerInfo.put("state", "Terminated");
                        containerInfo.put("reason", state.getTerminated().getReason());
                        containerInfo.put("message", state.getTerminated().getMessage());
                        containerInfo.put("exitCode", state.getTerminated().getExitCode());
                    }
                    
                    if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                        ContainerStateTerminated last = cs.getLastState().getTerminated();
                        containerInfo.put("lastTerminated", Map.of(
                            "reason", last.getReason() != null ? last.getReason() : "",
                            "exitCode", last.getExitCode(),
                            "message", last.getMessage() != null ? last.getMessage() : ""
                        ));
                    }
                    
                    containerStatuses.add(containerInfo);
                }
            }
            debugInfo.put("containerStatuses", containerStatuses);
            debugInfo.put("labels", pod.getMetadata().getLabels());
            
            List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
            if (owners != null && !owners.isEmpty()) {
                List<Map<String, String>> ownerInfo = owners.stream()
                    .map(o -> Map.of("kind", o.getKind(), "name", o.getName()))
                    .collect(Collectors.toList());
                debugInfo.put("owners", ownerInfo);
            }
            
            Log.info(MessageFormat.format("Successfully retrieved debug info for pod: {0}/{1}", namespace, podName));
            return debugInfo;
            
        } catch (Exception e) {
            Log.error("Error debugging pod", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Get Kubernetes events for a namespace or specific pod
     * @param namespace The Kubernetes namespace to get events from (e.g., 'default', 'kube-system')
     * @param podName Optional: The exact name of a specific pod to filter events for
     * @param limit Optional: Maximum number of events to return (default: 50)
     */
    @Tool("Get Kubernetes events for a namespace or specific pod")
    public Map<String, Object> getEvents(String namespace, String podName, Integer limit) {
        Log.info("=== Executing Tool: getEvents ===");
        
        if (namespace == null || namespace.isEmpty()) {
            return Map.of("error", "namespace is required and cannot be empty");
        }
        
        int eventLimit = (limit != null && limit > 0) ? limit : 50;
        Log.info(MessageFormat.format("Getting events for namespace: {0}, pod: {1}, limit: {2}", namespace, podName, eventLimit));
        
        try {
            List<Event> events = k8sClient.v1().events()
                .inNamespace(namespace)
                .list()
                .getItems();
            
            if (podName != null && !podName.isEmpty()) {
                events = events.stream()
                    .filter(e -> e.getInvolvedObject() != null && 
                                podName.equals(e.getInvolvedObject().getName()))
                    .collect(Collectors.toList());
            }
            
            events.sort((e1, e2) -> {
                String t1 = e1.getLastTimestamp() != null ? e1.getLastTimestamp() : 
                            e1.getMetadata().getCreationTimestamp();
                String t2 = e2.getLastTimestamp() != null ? e2.getLastTimestamp() : 
                            e2.getMetadata().getCreationTimestamp();
                return t2.compareTo(t1);
            });
            
            events = events.stream().limit(eventLimit).collect(Collectors.toList());
            
            List<Map<String, Object>> eventList = events.stream()
                .map(e -> Map.of(
                    "type", e.getType() != null ? e.getType() : "Normal",
                    "reason", e.getReason() != null ? e.getReason() : "",
                    "message", e.getMessage() != null ? e.getMessage() : "",
                    "count", e.getCount() != null ? e.getCount() : 1,
                    "firstTimestamp", e.getFirstTimestamp() != null ? e.getFirstTimestamp() : "",
                    "lastTimestamp", e.getLastTimestamp() != null ? e.getLastTimestamp() : "",
                    "involvedObject", e.getInvolvedObject() != null ? Map.of(
                        "kind", e.getInvolvedObject().getKind(),
                        "name", e.getInvolvedObject().getName()
                    ) : Map.of()
                ))
                .collect(Collectors.toList());
            Log.info(MessageFormat.format("Retrieved {0} events", eventList.size()));
            
            
            return Map.of(
                "namespace", namespace,
                "eventCount", eventList.size(),
                "events", eventList
            );
            
        } catch (Exception e) {
            Log.error("Error getting events", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Get pod logs
     */
    /**
     * Get logs from a Kubernetes pod
     * @param namespace The Kubernetes namespace where the pod is located (e.g., 'default', 'kube-system')
     * @param podName The exact name of the pod to get logs from (e.g., 'my-app-7d8f9c5b6-xyz12')
     * @param containerName Optional: The name of the container within the pod (if pod has multiple containers)
     * @param previous Optional: Set to true to get logs from the previous terminated container instance
     * @param tailLines Optional: Number of lines to tail from the end of the logs (default: 200). Use 200+ to capture runtime errors, not just startup logs.
     */
    @Tool("Get logs from a Kubernetes pod. Returns recent log entries including ERROR, CRITICAL, and ALERT messages if present.")
    public Map<String, Object> getLogs(String namespace, String podName, String containerName, Boolean previous, Integer tailLines) {
        Log.info("=== Executing Tool: getLogs ===");
        
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required and cannot be empty");
        }
        
        boolean getPrevious = previous != null && previous;
        // Default to 200 lines to capture runtime errors, not just startup logs
        int lines = (tailLines != null && tailLines > 0) ? tailLines : 200;
        Log.info(MessageFormat.format("Getting logs for pod: {0}/{1}, container: {2}, previous: {3}, lines: {4}",
                namespace, podName, containerName, getPrevious, lines));
        
        
        try {
            // First, check if the pod exists
            Pod pod = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();
            
            if (pod == null) {
                String errorMsg = MessageFormat.format("Pod not found: {0}/{1}", namespace, podName);
                Log.warn(errorMsg);
                return Map.of("error", errorMsg);
            }
            
            // Validate and auto-detect container name if needed
            List<Container> containers = pod.getSpec().getContainers();
            String targetContainer;
            
            // If no container name specified, auto-detect efficiently
            if (containerName == null || containerName.isEmpty()) {
                if (containers != null && containers.size() == 1) {
                    // Single-container pod - use it directly without warning
                    targetContainer = containers.get(0).getName();
                    Log.debug(MessageFormat.format("Single-container pod. Using container: {0}", targetContainer));
                } else if (containers != null && containers.size() > 1) {
                    // Multi-container pod - default to the first non-sidecar container
                    // Typically istio-proxy, envoy, etc. are sidecars
                    targetContainer = containers.stream()
                        .filter(c -> !c.getName().contains("proxy") &&
                                   !c.getName().contains("envoy") &&
                                   !c.getName().contains("sidecar"))
                        .findFirst()
                        .map(Container::getName)
                        .orElse(containers.get(0).getName());
                    
                    Log.info(MessageFormat.format("Multi-container pod detected. Using container: {0}", targetContainer));
                } else {
                    targetContainer = null;
                }
            } else {
                // Container name provided - verify it exists in the pod
                final String requestedContainer = containerName;
                boolean containerExists = containers.stream()
                    .anyMatch(c -> c.getName().equals(requestedContainer));
                
                if (!containerExists) {
                    // Only log warning if multiple containers exist
                    if (containers != null && containers.size() > 1) {
                        Log.warn(MessageFormat.format("Container ''{0}'' not found in pod. Available containers: {1}. Auto-detecting...",
                            requestedContainer,
                            containers.stream().map(Container::getName).collect(Collectors.joining(", "))));
                    }
                    // Fall through to auto-detection for single-container pods
                    if (containers != null && containers.size() == 1) {
                        targetContainer = containers.get(0).getName();
                        Log.debug(MessageFormat.format("Using single available container: {0}", targetContainer));
                    } else if (containers != null && containers.size() > 1) {
                        targetContainer = containers.stream()
                            .filter(c -> !c.getName().contains("proxy") &&
                                       !c.getName().contains("envoy") &&
                                       !c.getName().contains("sidecar"))
                            .findFirst()
                            .map(Container::getName)
                            .orElse(containers.get(0).getName());
                    } else {
                        targetContainer = null;
                    }
                } else {
                    targetContainer = containerName;
                }
            }
            
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
            Log.info(MessageFormat.format("Retrieved {0} characters of logs", logs.length()));
            
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
    
    /**
     * Get pod metrics
     */
    /**
     * Get resource metrics (CPU and memory usage) for a Kubernetes pod. IMPORTANT: You must provide both the namespace and the exact pod name.
     * @param namespace The Kubernetes namespace where the pod is located (e.g., 'default', 'kube-system'). REQUIRED.
     * @param podName The exact name of the pod to get metrics for (e.g., 'my-app-7d8f9c5b6-xyz12'). REQUIRED. Do NOT leave this empty.
     */
    @Tool("Get resource metrics (CPU and memory usage) for a Kubernetes pod. IMPORTANT: You must provide both the namespace and the exact pod name.")
    public Map<String, Object> getMetrics(String namespace, String podName) {
        Log.info("=== Executing Tool: getMetrics ===");
        
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required and cannot be empty");
        }
        Log.info(MessageFormat.format("Getting metrics for pod: {0}/{1}", namespace, podName));
        
        
        try {
            // Try to get actual metrics from metrics-server
            try {
                PodMetrics metrics = k8sClient.top().pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .metric();
                
                if (metrics != null) {
                    List<Map<String, Object>> containerMetrics = metrics.getContainers().stream()
                        .map(c -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("name", c.getName());
                            m.put("cpu", c.getUsage().get("cpu").toString());
                            m.put("memory", c.getUsage().get("memory").toString());
                            return m;
                        })
                        .collect(Collectors.toList());
                    Log.info(MessageFormat.format("Retrieved actual metrics for {0} containers", containerMetrics.size()));
                    
                    return Map.of(
                        "namespace", namespace,
                        "podName", podName,
                        "timestamp", metrics.getTimestamp(),
                        "containers", containerMetrics
                    );
                }
            } catch (Exception metricsException) {
                Log.warn("Metrics-server not available, falling back to resource requests/limits");
            }
            
            // Fallback: Get resource requests and limits from pod spec
            Pod pod = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();
            
            if (pod == null) {
                return Map.of("error", "Pod not found: " + namespace + "/" + podName);
            }
            
            Map<String, Object> metricsInfo = new HashMap<>();
            metricsInfo.put("podName", podName);
            metricsInfo.put("namespace", namespace);
            metricsInfo.put("note", "Showing resource requests/limits (metrics-server not available for actual usage)");
            
            List<Map<String, Object>> containerResources = new ArrayList<>();
            if (pod.getSpec().getContainers() != null) {
                for (var container : pod.getSpec().getContainers()) {
                    ResourceRequirements resources = container.getResources();
                    
                    if (resources != null) {
                        Map<String, Object> containerInfo = new HashMap<>();
                        containerInfo.put("containerName", container.getName());
                        
                        // Requests
                        if (resources.getRequests() != null) {
                            Map<String, String> requests = new HashMap<>();
                            resources.getRequests().forEach((key, value) ->
                                requests.put(key, value.toString())
                            );
                            containerInfo.put("requests", requests);
                        }
                        
                        // Limits
                        if (resources.getLimits() != null) {
                            Map<String, String> limits = new HashMap<>();
                            resources.getLimits().forEach((key, value) ->
                                limits.put(key, value.toString())
                            );
                            containerInfo.put("limits", limits);
                        }
                        
                        containerResources.add(containerInfo);
                    }
                }
            }
            
            metricsInfo.put("containers", containerResources);
            Log.info(MessageFormat.format("Retrieved resource requests/limits for pod: {0}/{1}", namespace, podName));
            
            return metricsInfo;
            
        } catch (Exception e) {
            Log.error("Error getting metrics", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Inspect Kubernetes resources in a namespace. Use labelSelector to filter pods by labels (e.g., 'role=stable' or 'role=canary')
     * @param namespace The Kubernetes namespace to inspect (e.g., 'default', 'kube-system')
     * @param resourceType Optional: Type of resource to inspect ('deployment', 'pods', 'service', 'configmap'). Leave null to inspect all types.
     * @param resourceName Optional: Specific resource name to filter by
     * @param labelSelector Optional: Label selector to filter pods (e.g., 'role=stable', 'app=myapp')
     */
    @Tool("Inspect Kubernetes resources in a namespace. Use labelSelector to filter pods by labels (e.g., 'role=stable' or 'role=canary')")
    public Map<String, Object> inspectResources(String namespace, String resourceType, String resourceName, String labelSelector) {
        Log.info("=== Executing Tool: inspectResources ===");
        
        if (namespace == null || namespace.isEmpty()) {
            return Map.of("error", "namespace is required and cannot be empty");
        }
        Log.info(MessageFormat.format("Inspecting resources in namespace: {0}, type: {1}, name: {2}, labelSelector: {3}",
            namespace, resourceType, resourceName, labelSelector));
        
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("namespace", namespace);
            
            if (resourceType == null || "deployment".equalsIgnoreCase(resourceType)) {
                List<Deployment> deployments = k8sClient.apps().deployments()
                    .inNamespace(namespace)
                    .list()
                    .getItems();
                
                if (resourceName != null && !resourceName.isEmpty()) {
                    deployments = deployments.stream()
                        .filter(d -> resourceName.equals(d.getMetadata().getName()))
                        .collect(Collectors.toList());
                }
                
                List<Map<String, Object>> deploymentInfo = deployments.stream()
                    .map(d -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", d.getMetadata().getName());
                        info.put("replicas", d.getStatus().getReplicas() != null ? d.getStatus().getReplicas() : 0);
                        info.put("availableReplicas", d.getStatus().getAvailableReplicas() != null ? d.getStatus().getAvailableReplicas() : 0);
                        info.put("readyReplicas", d.getStatus().getReadyReplicas() != null ? d.getStatus().getReadyReplicas() : 0);
                        info.put("labels", d.getMetadata().getLabels() != null ? d.getMetadata().getLabels() : Map.of());
                        return info;
                    })
                    .collect(Collectors.toList());
                
                result.put("deployments", deploymentInfo);
            }
            
            if (resourceType == null || "pods".equalsIgnoreCase(resourceType)) {
                List<Pod> pods;
                
                // Apply label selector if provided (takes priority over resourceName)
                if (labelSelector != null && !labelSelector.isEmpty()) {
                    Log.info(MessageFormat.format("Applying label selector: {0}", labelSelector));
                    pods = k8sClient.pods()
                        .inNamespace(namespace)
                        .withLabels(parseLabelSelector(labelSelector))
                        .list()
                        .getItems();
                    Log.info(MessageFormat.format("Found {0} pods matching label selector", pods.size()));
                } else if (resourceName != null && !resourceName.isEmpty()) {
                    // Only filter by resourceName if labelSelector is not provided
                    Log.info(MessageFormat.format("Filtering by resource name: {0}", resourceName));
                    pods = k8sClient.pods()
                        .inNamespace(namespace)
                        .list()
                        .getItems()
                        .stream()
                        .filter(p -> resourceName.equals(p.getMetadata().getName()))
                        .collect(Collectors.toList());
                } else {
                    // No filtering - get all pods
                    pods = k8sClient.pods()
                        .inNamespace(namespace)
                        .list()
                        .getItems();
                }
                
                // Limit to 3 pods per group for performance (agent only needs representative samples)
                List<Map<String, Object>> podInfo = pods.stream()
                    .limit(3)
                    .map(p -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", p.getMetadata().getName());
                        info.put("phase", p.getStatus().getPhase());
                        info.put("podIP", p.getStatus().getPodIP() != null ? p.getStatus().getPodIP() : "");
                        // Only include essential labels (role, app) to reduce context size
                        Map<String, String> labels = p.getMetadata().getLabels();
                        if (labels != null) {
                            Map<String, String> essentialLabels = new HashMap<>();
                            if (labels.containsKey("role")) essentialLabels.put("role", labels.get("role"));
                            if (labels.containsKey("app")) essentialLabels.put("app", labels.get("app"));
                            if (labels.containsKey("rollouts-pod-template-hash"))
                                essentialLabels.put("rollouts-pod-template-hash", labels.get("rollouts-pod-template-hash"));
                            info.put("labels", essentialLabels);
                        } else {
                            info.put("labels", Map.of());
                        }
                        
                        // Container readiness
                        if (p.getStatus().getContainerStatuses() != null) {
                            long readyCount = p.getStatus().getContainerStatuses().stream()
                                .filter(ContainerStatus::getReady)
                                .count();
                            info.put("readyContainers", readyCount + "/" + p.getStatus().getContainerStatuses().size());
                        }
                        
                        return info;
                    })
                    .collect(Collectors.toList());
                
                // Add summary if we truncated the list
                if (pods.size() > 3) {
                    Log.info(MessageFormat.format("Truncated pod list from {0} to 3 for performance", pods.size()));
                }
                
                result.put("pods", podInfo);
            }
            
            if (resourceType == null || "service".equalsIgnoreCase(resourceType)) {
                List<Service> services = k8sClient.services()
                    .inNamespace(namespace)
                    .list()
                    .getItems();
                
                if (resourceName != null && !resourceName.isEmpty()) {
                    services = services.stream()
                        .filter(s -> resourceName.equals(s.getMetadata().getName()))
                        .collect(Collectors.toList());
                }
                
                List<Map<String, Object>> serviceInfo = services.stream()
                    .map(s -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", s.getMetadata().getName());
                        info.put("type", s.getSpec().getType());
                        info.put("clusterIP", s.getSpec().getClusterIP() != null ? s.getSpec().getClusterIP() : "");
                        
                        // Enhanced port information
                        if (s.getSpec().getPorts() != null) {
                            List<Map<String, Object>> ports = s.getSpec().getPorts().stream()
                                .map(p -> {
                                    Map<String, Object> port = new HashMap<>();
                                    port.put("name", p.getName() != null ? p.getName() : "");
                                    port.put("port", p.getPort());
                                    port.put("targetPort", p.getTargetPort() != null ? p.getTargetPort().toString() : "");
                                    port.put("protocol", p.getProtocol() != null ? p.getProtocol() : "TCP");
                                    return port;
                                })
                                .collect(Collectors.toList());
                            info.put("ports", ports);
                        }
                        
                        info.put("selector", s.getSpec().getSelector() != null ?
                            s.getSpec().getSelector() : Map.of());
                        
                        return info;
                    })
                    .collect(Collectors.toList());
                
                result.put("services", serviceInfo);
            }
            
            if (resourceType == null || "configmap".equalsIgnoreCase(resourceType)) {
                List<ConfigMap> configMaps = k8sClient.configMaps()
                    .inNamespace(namespace)
                    .list()
                    .getItems();
                
                if (resourceName != null && !resourceName.isEmpty()) {
                    configMaps = configMaps.stream()
                        .filter(cm -> resourceName.equals(cm.getMetadata().getName()))
                        .collect(Collectors.toList());
                }
                
                List<Map<String, Object>> configMapInfo = configMaps.stream()
                    .map(cm -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", cm.getMetadata().getName());
                        
                        // Only include data keys, not full content for security
                        if (cm.getData() != null) {
                            info.put("dataKeys", cm.getData().keySet());
                        }
                        
                        return info;
                    })
                    .collect(Collectors.toList());
                
                result.put("configMaps", configMapInfo);
            }
            
            Log.info("Successfully inspected resources");
            return result;
            
        } catch (Exception e) {
            Log.error("Error inspecting resources", e);
            return Map.of("error", e.getMessage());
        }
    }
    
    /**
     * Parse label selector string (e.g., "role=canary" or "app=myapp,env=prod") into a Map
     */
    private Map<String, String> parseLabelSelector(String labelSelector) {
        if (labelSelector == null || labelSelector.trim().isEmpty()) {
            return Map.of();
        }
        
        Map<String, String> labels = new HashMap<>();
        String[] pairs = labelSelector.split(",");
        
        for (String pair : pairs) {
            String[] keyValue = pair.trim().split("=", 2);
            if (keyValue.length == 2) {
                labels.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        
        return labels;
    }

    /**
     * Fetch application metrics from a pod's Prometheus metrics endpoint
     * @param namespace The Kubernetes namespace where the pod is located
     * @param podName The exact name of the pod to fetch metrics from
     * @param metricsPath The path to the metrics endpoint (default: /q/metrics)
     * @param port The port number for the metrics endpoint (default: 8080)
     */
    @Tool("Fetch application metrics from a pod's Prometheus metrics endpoint. Returns error rates, request counts, latency, and custom application metrics.")
    public Map<String, Object> fetchApplicationMetrics(String namespace, String podName, String metricsPath, Integer port) {
        Log.info("=== Executing Tool: fetchApplicationMetrics ===");
        
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required and cannot be empty");
        }
        
        String path = (metricsPath != null && !metricsPath.isEmpty()) ? metricsPath : "/q/metrics";
        int targetPort = (port != null && port > 0) ? port : 8080;
        
        Log.info(MessageFormat.format("Fetching application metrics from pod: {0}/{1} at {2}:{3}",
                namespace, podName, path, targetPort));
        
        try {
            Pod pod = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName)
                .get();
            
            if (pod == null) {
                return Map.of("error", MessageFormat.format("Pod not found: {0}/{1}", namespace, podName));
            }
            
            String podIP = pod.getStatus().getPodIP();
            if (podIP == null || podIP.isEmpty()) {
                return Map.of("error", "Pod IP not available - pod may not be running");
            }
            
            try {
                String metricsUrl = MessageFormat.format("http://{0}:{1}{2}", podIP, targetPort, path);
                Log.info(MessageFormat.format("Fetching metrics from URL: {0}", metricsUrl));
                
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
                    return Map.of(
                        "error", "Failed to fetch metrics",
                        "statusCode", response.statusCode()
                    );
                }
                
                Map<String, Object> parsedMetrics = parsePrometheusMetrics(response.body());
                parsedMetrics.put("namespace", namespace);
                parsedMetrics.put("podName", podName);
                parsedMetrics.put("podIP", podIP);
                
                Log.info(MessageFormat.format("Successfully fetched metrics from pod: {0}/{1}", namespace, podName));
                return parsedMetrics;
                
            } catch (java.io.IOException | InterruptedException e) {
                Log.error("Error fetching metrics from pod", e);
                return Map.of(
                    "error", "Failed to connect to pod metrics endpoint",
                    "details", e.getMessage(),
                    "podIP", podIP
                );
            }
            
        } catch (Exception e) {
            Log.error("Error fetching application metrics", e);
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> parsePrometheusMetrics(String metricsText) {
        Map<String, Object> metrics = new HashMap<>();
        
        String[] lines = metricsText.split("\n");
        for (String line : lines) {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                continue;
            }
            
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
    
    /**
     * Fetches both stable and canary pod information and logs in a single call.
     * 
     * Functionality:
     * 1. Fetches all pods with role=stable label
     * 2. Fetches all pods with role=canary label  
     * 3. Gets logs from the first stable pod (if exists)
     * 4. Gets logs from the first canary pod (if exists)
     * 5. Returns combined data structure with pod info and logs for both
     * 
     * 
     * @param namespace The Kubernetes namespace (e.g., 'default')
     * @param containerName The container name to get logs from (e.g., 'quarkus-demo')
     * @param tailLines Number of log lines to fetch per pod (default: 200)
     * @return Combined diagnostic data for both stable and canary deployments
     */
    @Tool("canary diagnostics - fetches both stable and canary pod info and logs.")
    @RunOnVirtualThread
    public Map<String, Object> getCanaryDiagnostics(String namespace, String containerName, Integer tailLines) {
        Log.info("=== Executing Tool: getCanaryDiagnostics (with virtual threads) ===");
        
        if (namespace == null || namespace.isEmpty()) {
            return Map.of("error", "namespace is required");
        }
        
        int lines = (tailLines != null && tailLines > 0) ? tailLines : 200;
        Log.info(MessageFormat.format("Getting canary diagnostics for namespace: {0}, container: {1}, lines: {2}",
                namespace, containerName, lines));
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("namespace", namespace);
            
            // Fetch stable and canary pods in parallel using CompletableFuture
            CompletableFuture<List<Pod>> stablePodsFuture = CompletableFuture.supplyAsync(() ->
                k8sClient.pods()
                    .inNamespace(namespace)
                    .withLabels(Map.of("role", "stable"))
                    .list()
                    .getItems()
            );
            
            CompletableFuture<List<Pod>> canaryPodsFuture = CompletableFuture.supplyAsync(() ->
                k8sClient.pods()
                    .inNamespace(namespace)
                    .withLabels(Map.of("role", "canary"))
                    .list()
                    .getItems()
            );
            
            // Wait for both pod lists
            List<Pod> stablePods = stablePodsFuture.join();
            List<Pod> canaryPods = canaryPodsFuture.join();
            
            // Process stable and canary pods in parallel
            CompletableFuture<Map<String, Object>> stableInfoFuture = CompletableFuture.supplyAsync(() -> {
                Map<String, Object> stableInfo = new HashMap<>();
                if (!stablePods.isEmpty()) {
                    Pod stablePod = stablePods.get(0);
                    stableInfo.put("podName", stablePod.getMetadata().getName());
                    stableInfo.put("phase", stablePod.getStatus().getPhase());
                    stableInfo.put("podCount", stablePods.size());
                    
                    if (stablePod.getStatus().getContainerStatuses() != null) {
                        long readyCount = stablePod.getStatus().getContainerStatuses().stream()
                            .filter(ContainerStatus::getReady)
                            .count();
                        stableInfo.put("readyContainers", readyCount + "/" + stablePod.getStatus().getContainerStatuses().size());
                    }
                    
                    // Pass null/empty to let getLogs auto-detect the correct container name
                    String actualContainerName = (containerName != null && !containerName.isEmpty()) ? containerName : null;
                    Map<String, Object> logsResult = getLogs(namespace, stablePod.getMetadata().getName(), actualContainerName, false, lines);
                    if (logsResult.containsKey("logs")) {
                        stableInfo.put("logs", logsResult.get("logs"));
                    } else if (logsResult.containsKey("error")) {
                        stableInfo.put("logsError", logsResult.get("error"));
                    }
                } else {
                    stableInfo.put("error", "No stable pods found");
                }
                return stableInfo;
            });
            
            CompletableFuture<Map<String, Object>> canaryInfoFuture = CompletableFuture.supplyAsync(() -> {
                Map<String, Object> canaryInfo = new HashMap<>();
                if (!canaryPods.isEmpty()) {
                    Pod canaryPod = canaryPods.get(0);
                    canaryInfo.put("podName", canaryPod.getMetadata().getName());
                    canaryInfo.put("phase", canaryPod.getStatus().getPhase());
                    canaryInfo.put("podCount", canaryPods.size());
                    
                    if (canaryPod.getStatus().getContainerStatuses() != null) {
                        long readyCount = canaryPod.getStatus().getContainerStatuses().stream()
                            .filter(ContainerStatus::getReady)
                            .count();
                        canaryInfo.put("readyContainers", readyCount + "/" + canaryPod.getStatus().getContainerStatuses().size());
                    }
                    
                    // Pass null/empty to let getLogs auto-detect the correct container name
                    String actualContainerName = (containerName != null && !containerName.isEmpty()) ? containerName : null;
                    Map<String, Object> logsResult = getLogs(namespace, canaryPod.getMetadata().getName(), actualContainerName, false, lines);
                    if (logsResult.containsKey("logs")) {
                        canaryInfo.put("logs", logsResult.get("logs"));
                    } else if (logsResult.containsKey("error")) {
                        canaryInfo.put("logsError", logsResult.get("error"));
                    }
                } else {
                    canaryInfo.put("error", "No canary pods found");
                }
                return canaryInfo;
            });
            
            // Wait for both processing tasks to complete
            result.put("stable", stableInfoFuture.join());
            result.put("canary", canaryInfoFuture.join());
            
            Log.info("Successfully retrieved canary diagnostics (parallel execution)");
            return result;
            
        } catch (Exception e) {
            Log.error("Error getting canary diagnostics", e);
            return Map.of("error", e.getMessage());
        }
    }
}

