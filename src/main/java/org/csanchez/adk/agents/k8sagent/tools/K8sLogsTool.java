package org.csanchez.adk.agents.k8sagent.tools;

import com.google.adk.tools.BaseTool;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for retrieving Kubernetes pod logs
 */
public class K8sLogsTool extends BaseTool {
	
	private static final Logger logger = LoggerFactory.getLogger(K8sLogsTool.class);
	private final KubernetesClient k8sClient;
	private static final int DEFAULT_TAIL_LINES = 100;
	
	public K8sLogsTool() {
		super(
			"get_pod_logs",
			"Get logs from a Kubernetes pod. Retrieves logs from all containers. Requires: namespace, podName. Optional: tailLines (default 100), previous (boolean, default false)"
		);
		this.k8sClient = new KubernetesClientBuilder().build();
	}
	
	public Object execute(Map<String, Object> params) {
		logger.info("=== Executing Tool: get_pod_logs ===");
		
		String namespace = (String) params.get("namespace");
		String podName = (String) params.get("podName");
		Integer tailLines = params.containsKey("tailLines") ? 
			((Number) params.get("tailLines")).intValue() : DEFAULT_TAIL_LINES;
		Boolean previous = params.containsKey("previous") ? 
			(Boolean) params.get("previous") : false;
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		
		logger.info("Getting logs for pod: {}/{}, previous: {}", namespace, podName, previous);
		
		try {
			Pod pod = k8sClient.pods()
				.inNamespace(namespace)
				.withName(podName)
				.get();
			
			if (pod == null) {
				return Map.of("error", "Pod not found: " + namespace + "/" + podName);
			}
			
			List<Map<String, Object>> containerLogs = new ArrayList<>();
			
			// Get logs from all containers
			List<ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses();
			if (containerStatuses != null) {
				for (ContainerStatus cs : containerStatuses) {
					String containerName = cs.getName();
					
					try {
						String logs = k8sClient.pods()
							.inNamespace(namespace)
							.withName(podName)
							.inContainer(containerName)
							.tailingLines(tailLines)
							.withPrettyOutput()
							.getLog();
						
						Map<String, Object> containerLog = new HashMap<>();
						containerLog.put("containerName", containerName);
						containerLog.put("restartCount", cs.getRestartCount());
						containerLog.put("logs", logs != null ? logs : "No logs available");
						
						// Note about previous logs if container has restarted
						if (previous && cs.getRestartCount() > 0) {
							containerLog.put("note", "Container has restarted " + cs.getRestartCount() + " times. Use kubectl logs --previous for terminated container logs.");
						}
						
						// Analyze logs for common error patterns
						Map<String, Object> analysis = analyzeLogs(logs);
						containerLog.put("analysis", analysis);
						
						containerLogs.add(containerLog);
						
					} catch (Exception e) {
						logger.warn("Error getting logs for container {}: {}", containerName, e.getMessage());
						containerLogs.add(Map.of(
							"containerName", containerName,
							"error", e.getMessage()
						));
					}
				}
			}
			
			logger.info("Retrieved logs from {} containers", containerLogs.size());
			
			return Map.of(
				"namespace", namespace,
				"podName", podName,
				"containers", containerLogs
			);
			
		} catch (Exception e) {
			logger.error("Error getting pod logs", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	/**
	 * Analyze logs for common error patterns
	 */
	private Map<String, Object> analyzeLogs(String logs) {
		if (logs == null || logs.isEmpty()) {
			return Map.of("hasErrors", false);
		}
		
		String lowerLogs = logs.toLowerCase();
		boolean hasErrors = lowerLogs.contains("error") || 
			lowerLogs.contains("exception") ||
			lowerLogs.contains("fatal") ||
			lowerLogs.contains("panic");
		
		boolean hasWarnings = lowerLogs.contains("warn");
		
		// Count error lines
		long errorCount = logs.lines()
			.filter(line -> {
				String lower = line.toLowerCase();
				return lower.contains("error") || lower.contains("exception");
			})
			.count();
		
		// Detect OOMKilled
		boolean oomDetected = lowerLogs.contains("out of memory") ||
			lowerLogs.contains("oomkilled");
		
		// Detect connection issues
		boolean connectionIssues = lowerLogs.contains("connection refused") ||
			lowerLogs.contains("connection timeout") ||
			lowerLogs.contains("unable to connect");
		
		Map<String, Object> analysis = new HashMap<>();
		analysis.put("hasErrors", hasErrors);
		analysis.put("hasWarnings", hasWarnings);
		analysis.put("errorCount", errorCount);
		analysis.put("oomDetected", oomDetected);
		analysis.put("connectionIssues", connectionIssues);
		
		return analysis;
	}
}


