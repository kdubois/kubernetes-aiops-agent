package org.csanchez.adk.agents.k8sagent.tools;

import com.google.adk.tools.BaseTool;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool for retrieving Kubernetes metrics
 */
public class K8sMetricsTool extends BaseTool {
	
	private static final Logger logger = LoggerFactory.getLogger(K8sMetricsTool.class);
	private final KubernetesClient k8sClient;
	
	public K8sMetricsTool() {
		super(
			"get_pod_metrics",
			"Get resource metrics for a Kubernetes pod. Requires: namespace, podName"
		);
		this.k8sClient = new KubernetesClientBuilder().build();
	}
	
	public Object execute(Map<String, Object> params) {
		logger.info("=== Executing Tool: get_pod_metrics ===");
		
		String namespace = (String) params.get("namespace");
		String podName = (String) params.get("podName");
		
		if (namespace == null || podName == null) {
			return Map.of("error", "namespace and podName are required");
		}
		
		logger.info("Getting metrics for pod: {}/{}", namespace, podName);
		
		try {
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
			
			// Get resource requests and limits from containers
			if (pod.getSpec().getContainers() != null) {
				pod.getSpec().getContainers().forEach(container -> {
					ResourceRequirements resources = container.getResources();
					
					if (resources != null) {
						Map<String, Object> containerResources = new HashMap<>();
						containerResources.put("containerName", container.getName());
						
						// Requests
						if (resources.getRequests() != null) {
							Map<String, String> requests = new HashMap<>();
							resources.getRequests().forEach((key, value) -> 
								requests.put(key, value.toString())
							);
							containerResources.put("requests", requests);
						}
						
						// Limits
						if (resources.getLimits() != null) {
							Map<String, String> limits = new HashMap<>();
							resources.getLimits().forEach((key, value) -> 
								limits.put(key, value.toString())
							);
							containerResources.put("limits", limits);
						}
						
						metricsInfo.put(container.getName(), containerResources);
					}
				});
			}
			
			// Try to get actual metrics from metrics-server if available
			// Note: This requires metrics-server to be installed in the cluster
			try {
				// This would require the metrics API, which is an optional extension
				// For now, we'll just note that metrics-server is needed
				metricsInfo.put("note", "Install metrics-server for real-time CPU/Memory usage");
			} catch (Exception e) {
				logger.debug("Metrics server not available: {}", e.getMessage());
			}
			
			logger.info("Retrieved metrics for pod: {}/{}", namespace, podName);
			return metricsInfo;
			
		} catch (Exception e) {
			logger.error("Error getting pod metrics", e);
			return Map.of("error", e.getMessage());
		}
	}
}


