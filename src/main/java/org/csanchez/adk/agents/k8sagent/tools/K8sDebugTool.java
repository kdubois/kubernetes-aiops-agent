package org.csanchez.adk.agents.k8sagent.tools;


import com.google.adk.tools.Annotations.Schema;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool for debugging Kubernetes pods
 */
public class K8sDebugTool {
	
	private static final Logger logger = LoggerFactory.getLogger(K8sDebugTool.class);
	private static final KubernetesClient k8sClient = new KubernetesClientBuilder().build();
	
	public static Map<String, Object> debugPod(
		@Schema(name = "namespace", description = "The Kubernetes namespace of the pod") String namespace,
		@Schema(name = "podName", description = "The name of the pod to debug") String podName
	) {
		logger.info("=== Executing Tool: debug_kubernetes_pod ===");
		
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
			
			// Build comprehensive debug info
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
					
					// Container state
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
					
					// Last terminated state (for crash loop detection)
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
			
			// Labels
			debugInfo.put("labels", pod.getMetadata().getLabels());
			
			// Owner references (Deployment, StatefulSet, etc.)
			List<OwnerReference> owners = pod.getMetadata().getOwnerReferences();
			if (owners != null && !owners.isEmpty()) {
				List<Map<String, String>> ownerInfo = owners.stream()
					.map(o -> Map.of(
						"kind", o.getKind(),
						"name", o.getName()
					))
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
}


