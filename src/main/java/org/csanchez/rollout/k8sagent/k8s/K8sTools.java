package org.csanchez.rollout.k8sagent.k8s;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Kubernetes tools for LangChain4j
 */
@ApplicationScoped
public class K8sTools {
    
    @Inject
    KubernetesClient k8sClient;
    
    /**
     * Debug a Kubernetes pod
     */
    @Tool("Debug a Kubernetes pod to get detailed information about its status and conditions")
    public Map<String, Object> debugPod(
            @P("The Kubernetes namespace where the pod is located (e.g., 'default', 'kube-system')") String namespace,
            @P("The exact name of the pod to debug (e.g., 'my-app-7d8f9c5b6-xyz12')") String podName
    ) {
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
     * Get Kubernetes events
     */
    @Tool("Get Kubernetes events for a namespace or specific pod")
    public Map<String, Object> getEvents(
            @P("The Kubernetes namespace to get events from (e.g., 'default', 'kube-system')") String namespace,
            @P("Optional: The exact name of a specific pod to filter events for") String podName,
            @P("Optional: Maximum number of events to return (default: 50)") Integer limit
    ) {
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
    @Tool("Get logs from a Kubernetes pod")
    public Map<String, Object> getLogs(
            @P("The Kubernetes namespace where the pod is located (e.g., 'default', 'kube-system')") String namespace,
            @P("The exact name of the pod to get logs from (e.g., 'my-app-7d8f9c5b6-xyz12')") String podName,
            @P("Optional: The name of the container within the pod (if pod has multiple containers)") String containerName,
            @P("Optional: Set to true to get logs from the previous terminated container instance") Boolean previous,
            @P("Optional: Number of lines to tail from the end of the logs (default: 100)") Integer tailLines
    ) {
        Log.info("=== Executing Tool: getLogs ===");
        
        if (namespace == null || namespace.isEmpty() || podName == null || podName.isEmpty()) {
            return Map.of("error", "namespace and podName are required and cannot be empty");
        }
        
        boolean getPrevious = previous != null && previous;
        int lines = (tailLines != null && tailLines > 0) ? tailLines : 100;
        Log.info(MessageFormat.format("Getting logs for pod: {0}/{1}, container: {2}, previous: {3}, lines: {4}",
                namespace, podName, containerName, getPrevious, lines));
        
        
        try {
            var podResource = k8sClient.pods()
                .inNamespace(namespace)
                .withName(podName);
            
            String logs;
            if (containerName != null && !containerName.isEmpty()) {
                logs = podResource.inContainer(containerName).tailingLines(lines).getLog(getPrevious);
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
                "container", containerName != null ? containerName : "default",
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
    @Tool("Get resource metrics (CPU and memory usage) for a Kubernetes pod. IMPORTANT: You must provide both the namespace and the exact pod name.")
    public Map<String, Object> getMetrics(
            @P("The Kubernetes namespace where the pod is located (e.g., 'default', 'kube-system'). REQUIRED.") String namespace,
            @P("The exact name of the pod to get metrics for (e.g., 'my-app-7d8f9c5b6-xyz12'). REQUIRED. Do NOT leave this empty.") String podName
    ) {
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
     * Inspect Kubernetes resources
     */
    @Tool("Inspect Kubernetes resources in a namespace. Use labelSelector to filter pods by labels (e.g., 'role=stable' or 'role=canary')")
    public Map<String, Object> inspectResources(
            @P("The Kubernetes namespace to inspect (e.g., 'default', 'kube-system')") String namespace,
            @P("Optional: Type of resource to inspect ('deployment', 'pods', 'service', 'configmap'). Leave null to inspect all types.") String resourceType,
            @P("Optional: Specific resource name to filter by") String resourceName,
            @P("Optional: Label selector to filter pods (e.g., 'role=stable', 'app=myapp')") String labelSelector
    ) {
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
                
                // Apply label selector if provided
                if (labelSelector != null && !labelSelector.isEmpty()) {
                    Log.info(MessageFormat.format("Applying label selector: {0}", labelSelector));
                    pods = k8sClient.pods()
                        .inNamespace(namespace)
                        .withLabels(parseLabelSelector(labelSelector))
                        .list()
                        .getItems();
                    Log.info(MessageFormat.format("Found {0} pods matching label selector", pods.size()));
                } else {
                    pods = k8sClient.pods()
                        .inNamespace(namespace)
                        .list()
                        .getItems();
                }
                
                if (resourceName != null && !resourceName.isEmpty()) {
                    pods = pods.stream()
                        .filter(p -> resourceName.equals(p.getMetadata().getName()))
                        .collect(Collectors.toList());
                }
                
                List<Map<String, Object>> podInfo = pods.stream()
                    .map(p -> {
                        Map<String, Object> info = new HashMap<>();
                        info.put("name", p.getMetadata().getName());
                        info.put("phase", p.getStatus().getPhase());
                        info.put("podIP", p.getStatus().getPodIP() != null ? p.getStatus().getPodIP() : "");
                        info.put("labels", p.getMetadata().getLabels() != null ? p.getMetadata().getLabels() : Map.of());
                        
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
}

