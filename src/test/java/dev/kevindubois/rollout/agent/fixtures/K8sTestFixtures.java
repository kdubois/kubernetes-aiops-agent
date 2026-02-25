package dev.kevindubois.rollout.agent.fixtures;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Test fixtures for creating Kubernetes objects for testing.
 * Provides factory methods to create common K8s resources with sensible defaults.
 */
public class K8sTestFixtures {

    /**
     * Creates a test Pod with default configuration
     */
    public static Pod createTestPod(String name, String namespace) {
        return createTestPod(name, namespace, "Running", true);
    }

    /**
     * Creates a test Pod with custom status and readiness
     */
    public static Pod createTestPod(String name, String namespace, String phase, boolean ready) {
        return new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Map.of(
                        "app", "test-app",
                        "version", "v1"
                    ))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("test-image:latest")
                        .addNewPort()
                            .withContainerPort(8080)
                            .withName("http")
                        .endPort()
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase(phase)
                    .addNewCondition()
                        .withType("Ready")
                        .withStatus(ready ? "True" : "False")
                        .withLastTransitionTime(Instant.now().toString())
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withReady(ready)
                        .withRestartCount(0)
                    .endContainerStatus()
                .endStatus()
                .build();
    }

    /**
     * Creates a test Pod with crash loop status
     */
    public static Pod createCrashLoopPod(String name, String namespace) {
        return new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Map.of("app", "test-app"))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("test-image:latest")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                    .addNewContainerStatus()
                        .withName("app")
                        .withReady(false)
                        .withRestartCount(5)
                        .withNewState()
                            .withNewWaiting()
                                .withReason("CrashLoopBackOff")
                                .withMessage("Back-off 5m0s restarting failed container")
                            .endWaiting()
                        .endState()
                    .endContainerStatus()
                .endStatus()
                .build();
    }

    /**
     * Creates a test Event
     */
    public static Event createTestEvent(String name, String namespace, String reason, String message, String type) {
        return new EventBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata()
                .withReason(reason)
                .withMessage(message)
                .withType(type)
                .withCount(1)
                .withFirstTimestamp(Instant.now().toString())
                .withLastTimestamp(Instant.now().toString())
                .withNewInvolvedObject()
                    .withKind("Pod")
                    .withName("test-pod")
                    .withNamespace(namespace)
                .endInvolvedObject()
                .build();
    }

    /**
     * Creates a warning event
     */
    public static Event createWarningEvent(String namespace, String reason, String message) {
        return createTestEvent("warning-event", namespace, reason, message, "Warning");
    }

    /**
     * Creates a normal event
     */
    public static Event createNormalEvent(String namespace, String reason, String message) {
        return createTestEvent("normal-event", namespace, reason, message, "Normal");
    }

    /**
     * Creates test PodMetrics
     */
    public static PodMetrics createTestPodMetrics(String name, String namespace, String cpuUsage, String memoryUsage) {
        return new PodMetricsBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata()
                .withContainers(List.of(
                    createContainerMetrics("app", cpuUsage, memoryUsage)
                ))
                .build();
    }

    /**
     * Creates ContainerMetrics
     */
    public static ContainerMetrics createContainerMetrics(String name, String cpuUsage, String memoryUsage) {
        ContainerMetrics metrics = new ContainerMetrics();
        metrics.setName(name);
        metrics.setUsage(Map.of(
            "cpu", new Quantity(cpuUsage),
            "memory", new Quantity(memoryUsage)
        ));
        return metrics;
    }

    /**
     * Creates a test Deployment
     */
    public static io.fabric8.kubernetes.api.model.apps.Deployment createTestDeployment(
            String name, String namespace, int replicas) {
        return new io.fabric8.kubernetes.api.model.apps.DeploymentBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Map.of("app", name))
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector()
                        .withMatchLabels(Map.of("app", name))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(Map.of("app", name))
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer()
                                .withName("app")
                                .withImage("test-image:latest")
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .withNewStatus()
                    .withReplicas(replicas)
                    .withReadyReplicas(replicas)
                    .withAvailableReplicas(replicas)
                .endStatus()
                .build();
    }

    /**
     * Creates a test Service
     */
    public static Service createTestService(String name, String namespace) {
        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Map.of("app", name))
                .endMetadata()
                .withNewSpec()
                    .withSelector(Map.of("app", name))
                    .addNewPort()
                        .withName("http")
                        .withPort(80)
                        .withTargetPort(new IntOrString(8080))
                        .withProtocol("TCP")
                    .endPort()
                    .withType("ClusterIP")
                .endSpec()
                .build();
    }

    /**
     * Creates a test Namespace
     */
    public static Namespace createTestNamespace(String name) {
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(Map.of("environment", "test"))
                .endMetadata()
                .withNewStatus()
                    .withPhase("Active")
                .endStatus()
                .build();
    }

    /**
     * Creates a test ConfigMap
     */
    public static ConfigMap createTestConfigMap(String name, String namespace, Map<String, String> data) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata()
                .withData(data)
                .build();
    }

    /**
     * Creates a test Secret
     */
    public static Secret createTestSecret(String name, String namespace, Map<String, String> data) {
        return new SecretBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata()
                .withType("Opaque")
                .withStringData(data)
                .build();
    }

    /**
     * Creates pod logs for testing
     */
    public static String createTestPodLogs(String podName, int lineCount, boolean includeErrors) {
        StringBuilder logs = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) {
            if (includeErrors && i % 5 == 0) {
                logs.append(String.format("ERROR [%s] Error occurred at line %d\n", podName, i));
            } else {
                logs.append(String.format("INFO [%s] Log line %d\n", podName, i));
            }
        }
        return logs.toString();
    }

    /**
     * Creates error logs for testing
     */
    public static String createErrorLogs(String errorMessage, int count) {
        StringBuilder logs = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            logs.append(String.format("ERROR: %s (occurrence %d)\n", errorMessage, i));
            logs.append("  at com.example.Service.method(Service.java:123)\n");
            logs.append("  at com.example.Controller.handle(Controller.java:45)\n");
        }
        return logs.toString();
    }
}

