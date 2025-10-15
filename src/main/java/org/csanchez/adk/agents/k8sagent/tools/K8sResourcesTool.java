package org.csanchez.adk.agents.k8sagent.tools;

import com.google.adk.tools.BaseTool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tool for inspecting Kubernetes resources
 */
public class K8sResourcesTool extends BaseTool {
	
	private static final Logger logger = LoggerFactory.getLogger(K8sResourcesTool.class);
	private final KubernetesClient k8sClient;
	
	public K8sResourcesTool() {
		super(
			"inspect_kubernetes_resources",
			"Inspect related Kubernetes resources (deployments, services, configmaps). Requires: namespace. Optional: resourceType (deployment, service, configmap), resourceName"
		);
		this.k8sClient = new KubernetesClientBuilder().build();
	}
	
	public Object execute(Map<String, Object> params) {
		logger.info("=== Executing Tool: inspect_kubernetes_resources ===");
		
		String namespace = (String) params.get("namespace");
		String resourceType = (String) params.get("resourceType");
		String resourceName = (String) params.get("resourceName");
		
		if (namespace == null) {
			return Map.of("error", "namespace is required");
		}
		
		logger.info("Inspecting resources in namespace: {}, type: {}, name: {}", 
			namespace, resourceType, resourceName);
		
		try {
			Map<String, Object> result = new HashMap<>();
			result.put("namespace", namespace);
			
			if (resourceType == null || "deployment".equalsIgnoreCase(resourceType)) {
				List<Map<String, Object>> deployments = getDeployments(namespace, resourceName);
				result.put("deployments", deployments);
			}
			
			if (resourceType == null || "service".equalsIgnoreCase(resourceType)) {
				List<Map<String, Object>> services = getServices(namespace, resourceName);
				result.put("services", services);
			}
			
			if (resourceType == null || "configmap".equalsIgnoreCase(resourceType)) {
				List<Map<String, Object>> configMaps = getConfigMaps(namespace, resourceName);
				result.put("configMaps", configMaps);
			}
			
			logger.info("Retrieved resource information for namespace: {}", namespace);
			return result;
			
		} catch (Exception e) {
			logger.error("Error inspecting resources", e);
			return Map.of("error", e.getMessage());
		}
	}
	
	private List<Map<String, Object>> getDeployments(String namespace, String name) {
		List<Deployment> deployments;
		
		if (name != null && !name.isEmpty()) {
			Deployment dep = k8sClient.apps().deployments()
				.inNamespace(namespace)
				.withName(name)
				.get();
			deployments = dep != null ? List.of(dep) : List.of();
		} else {
			deployments = k8sClient.apps().deployments()
				.inNamespace(namespace)
				.list()
				.getItems();
		}
		
		return deployments.stream()
			.map(d -> Map.of(
				"name", d.getMetadata().getName(),
				"replicas", d.getSpec().getReplicas() != null ? d.getSpec().getReplicas() : 0,
				"availableReplicas", d.getStatus().getAvailableReplicas() != null ? 
					d.getStatus().getAvailableReplicas() : 0,
				"readyReplicas", d.getStatus().getReadyReplicas() != null ? 
					d.getStatus().getReadyReplicas() : 0,
				"labels", d.getMetadata().getLabels() != null ? d.getMetadata().getLabels() : Map.of()
			))
			.collect(Collectors.toList());
	}
	
	private List<Map<String, Object>> getServices(String namespace, String name) {
		List<Service> services;
		
		if (name != null && !name.isEmpty()) {
			Service svc = k8sClient.services()
				.inNamespace(namespace)
				.withName(name)
				.get();
			services = svc != null ? List.of(svc) : List.of();
		} else {
			services = k8sClient.services()
				.inNamespace(namespace)
				.list()
				.getItems();
		}
		
		return services.stream()
			.map(s -> {
				Map<String, Object> serviceInfo = new HashMap<>();
				serviceInfo.put("name", s.getMetadata().getName());
				serviceInfo.put("type", s.getSpec().getType());
				serviceInfo.put("clusterIP", s.getSpec().getClusterIP());
				
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
					serviceInfo.put("ports", ports);
				}
				
				serviceInfo.put("selector", s.getSpec().getSelector() != null ? 
					s.getSpec().getSelector() : Map.of());
				
				return serviceInfo;
			})
			.collect(Collectors.toList());
	}
	
	private List<Map<String, Object>> getConfigMaps(String namespace, String name) {
		List<ConfigMap> configMaps;
		
		if (name != null && !name.isEmpty()) {
			ConfigMap cm = k8sClient.configMaps()
				.inNamespace(namespace)
				.withName(name)
				.get();
			configMaps = cm != null ? List.of(cm) : List.of();
		} else {
			configMaps = k8sClient.configMaps()
				.inNamespace(namespace)
				.list()
				.getItems();
		}
		
		return configMaps.stream()
			.map(cm -> {
				Map<String, Object> cmInfo = new HashMap<>();
				cmInfo.put("name", cm.getMetadata().getName());
				
				// Only include data keys, not full content for security
				if (cm.getData() != null) {
					cmInfo.put("dataKeys", cm.getData().keySet());
				}
				
				return cmInfo;
			})
			.collect(Collectors.toList());
	}
}


