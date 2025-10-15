package org.csanchez.adk.agents.k8sagent.tools;

import com.google.adk.tools.Annotations.Schema;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * All Kubernetes tools in one class for easier management with FunctionTool
 */
public class K8sTools {
	
	private static final Logger logger = LoggerFactory.getLogger(K8sTools.class);
	private static final KubernetesClient k8sClient = new KubernetesClientBuilder().build();
	
	// ==================== DEBUG POD ====================
	
	public static Map<String, Object> debugPod(
		@Schema(name = "namespace", description = "The Kubernetes namespace of the pod") String namespace,
		@Schema(name = "podName", description = "The name of the pod to debug") String podName
	) {
		logger.info("=== Executing Tool: debugPod ===");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		
		logger.info("Debugging pod: {}/{}", namespace, podName);
		
		try {
			Pod pod = k8sClient.pods()
				.inNamespace(namespace)
				.withName(podName)
				.get();
			
			if (pod == null) {
				return Map.of("error", "Pod not found: " + namespace + "/" + podName);
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
			
			logger.info("Successfully retrieved debug info for pod: {}/{}", namespace, podName);
			return debugInfo;
			
		} catch (Exception e) {
			logger.error("Error debugging pod", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	// ==================== GET EVENTS ====================
	
	public static Map<String, Object> getEvents(
		@Schema(name = "namespace", description = "The Kubernetes namespace") String namespace,
		@Schema(name = "podName", description = "Optional: filter events for a specific pod name") String podName,
		@Schema(name = "limit", description = "Optional: maximum number of events to return (default 50)") Integer limit
	) {
		logger.info("=== Executing Tool: getEvents ===");
		
		if (namespace == null) {
			return Map.of("error", "namespace is required");
		}
		
		int eventLimit = (limit != null && limit > 0) ? limit : 50;
		logger.info("Getting events for namespace: {}, pod: {}, limit: {}", namespace, podName, eventLimit);
		
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
			
			logger.info("Retrieved {} events", eventList.size());
			
			return Map.of(
				"namespace", namespace,
				"eventCount", eventList.size(),
				"events", eventList
			);
			
		} catch (Exception e) {
			logger.error("Error getting events", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	// ==================== GET LOGS ====================
	
	public static Map<String, Object> getLogs(
		@Schema(name = "namespace", description = "The Kubernetes namespace") String namespace,
		@Schema(name = "podName", description = "The name of the pod") String podName,
		@Schema(name = "containerName", description = "Optional: specific container name") String containerName,
		@Schema(name = "previous", description = "Optional: get logs from previous container instance (for crashed pods)") Boolean previous,
		@Schema(name = "tailLines", description = "Optional: number of lines from the end of logs (default 100)") Integer tailLines
	) {
		logger.info("=== Executing Tool: getLogs ===");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		
		boolean getPrevious = previous != null && previous;
		int lines = (tailLines != null && tailLines > 0) ? tailLines : 100;
		
		logger.info("Getting logs for pod: {}/{}, container: {}, previous: {}, lines: {}", 
			namespace, podName, containerName, getPrevious, lines);
		
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
			
			logger.info("Retrieved {} characters of logs", logs.length());
			
			return Map.of(
				"namespace", namespace,
				"podName", podName,
				"container", containerName != null ? containerName : "default",
				"previous", getPrevious,
				"logs", logs
			);
			
		} catch (Exception e) {
			logger.error("Error getting logs", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	// ==================== GET METRICS ====================
	
	public static Map<String, Object> getMetrics(
		@Schema(name = "namespace", description = "The Kubernetes namespace") String namespace,
		@Schema(name = "podName", description = "The name of the pod") String podName
	) {
		logger.info("=== Executing Tool: getMetrics ===");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		
		logger.info("Getting metrics for pod: {}/{}", namespace, podName);
		
		try {
			PodMetrics metrics = k8sClient.top().pods()
				.inNamespace(namespace)
				.withName(podName)
				.metric();
			
			if (metrics == null) {
				return Map.of("error", "Metrics not available (metrics-server might not be installed)");
			}
			
			List<Map<String, Object>> containerMetrics = metrics.getContainers().stream()
				.map(c -> {
					Map<String, Object> m = new HashMap<>();
					m.put("name", c.getName());
					m.put("cpu", c.getUsage().get("cpu").toString());
					m.put("memory", c.getUsage().get("memory").toString());
					return m;
				})
				.collect(Collectors.toList());
			
			logger.info("Retrieved metrics for {} containers", containerMetrics.size());
			
			return Map.of(
				"namespace", namespace,
				"podName", podName,
				"timestamp", metrics.getTimestamp(),
				"containers", containerMetrics
			);
			
		} catch (Exception e) {
			logger.error("Error getting metrics", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	// ==================== INSPECT RESOURCES ====================
	
	public static Map<String, Object> inspectResources(
		@Schema(name = "namespace", description = "The Kubernetes namespace") String namespace,
		@Schema(name = "resourceType", description = "Optional: specific resource type (deployment, service, configmap)") String resourceType,
		@Schema(name = "resourceName", description = "Optional: specific resource name") String resourceName
	) {
		logger.info("=== Executing Tool: inspectResources ===");
		
		if (namespace == null) {
			return Map.of("error", "namespace is required");
		}
		
		logger.info("Inspecting resources in namespace: {}, type: {}, name: {}", 
			namespace, resourceType, resourceName);
		
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("namespace", namespace);
			
			if (resourceType == null || "deployment".equalsIgnoreCase(resourceType)) {
				List<Deployment> deployments = k8sClient.apps().deployments()
					.inNamespace(namespace)
					.list()
					.getItems();
				
				if (resourceName != null) {
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
					return info;
				})
				.collect(Collectors.toList());
				
				result.put("deployments", deploymentInfo);
			}
			
			if (resourceType == null || "service".equalsIgnoreCase(resourceType)) {
				List<Service> services = k8sClient.services()
					.inNamespace(namespace)
					.list()
					.getItems();
				
				if (resourceName != null) {
					services = services.stream()
						.filter(s -> resourceName.equals(s.getMetadata().getName()))
						.collect(Collectors.toList());
				}
				
				List<Map<String, Object>> serviceInfo = services.stream()
					.map(s -> Map.of(
						"name", s.getMetadata().getName(),
						"type", s.getSpec().getType(),
						"clusterIP", s.getSpec().getClusterIP() != null ? s.getSpec().getClusterIP() : "",
						"ports", s.getSpec().getPorts().stream()
							.map(p -> p.getPort() + ":" + p.getTargetPort())
							.collect(Collectors.toList())
					))
					.collect(Collectors.toList());
				
				result.put("services", serviceInfo);
			}
			
			logger.info("Successfully inspected resources");
			return result;
			
		} catch (Exception e) {
			logger.error("Error inspecting resources", e);
			return Map.of("error", e.getMessage());
		}
	}
}

