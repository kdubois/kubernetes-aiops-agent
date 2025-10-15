package org.csanchez.adk.agents.k8sagent.tools;

import com.google.adk.tools.BaseTool;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool for retrieving Kubernetes events
 */
public class K8sEventsTool extends BaseTool {
	
	private static final Logger logger = LoggerFactory.getLogger(K8sEventsTool.class);
	private final KubernetesClient k8sClient;
	
	public K8sEventsTool() {
		super(
			"get_kubernetes_events",
			"Get recent Kubernetes events for a namespace or specific pod. Requires: namespace. Optional: podName, limit (default 50)"
		);
		this.k8sClient = new KubernetesClientBuilder().build();
	}
	
	public Object execute(Map<String, Object> params) {
		logger.info("=== Executing Tool: get_kubernetes_events ===");
		
		String namespace = (String) params.get("namespace");
		String podName = (String) params.get("podName");
		Integer limit = params.containsKey("limit") ? 
			((Number) params.get("limit")).intValue() : 50;
		
		if (namespace == null) {
			return Map.of("error", "namespace is required");
		}
		
		logger.info("Getting events for namespace: {}, pod: {}", namespace, podName);
		
		try {
			List<Event> events = k8sClient.v1().events()
				.inNamespace(namespace)
				.list()
				.getItems();
			
			// Filter by pod name if provided
			if (podName != null && !podName.isEmpty()) {
				events = events.stream()
					.filter(e -> {
						if (e.getInvolvedObject() != null) {
							return podName.equals(e.getInvolvedObject().getName());
						}
						return false;
					})
					.collect(Collectors.toList());
			}
			
			// Sort by last timestamp (most recent first)
			events.sort((e1, e2) -> {
				String t1 = e1.getLastTimestamp() != null ? e1.getLastTimestamp() : e1.getMetadata().getCreationTimestamp();
				String t2 = e2.getLastTimestamp() != null ? e2.getLastTimestamp() : e2.getMetadata().getCreationTimestamp();
				return t2.compareTo(t1);
			});
			
			// Limit results
			events = events.stream().limit(limit).collect(Collectors.toList());
			
			// Convert to simpler format
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
}


