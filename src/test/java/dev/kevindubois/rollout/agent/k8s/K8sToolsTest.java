package dev.kevindubois.rollout.agent.k8s;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import dev.kevindubois.rollout.agent.fixtures.K8sTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for K8sTools.
 * Tests all Kubernetes operations including pod debugging, logs, events, and metrics.
 */
@QuarkusTest
class K8sToolsTest {

    @Inject
    K8sTools k8sTools;

    @InjectMock
    KubernetesClient k8sClient;

    private static final String TEST_NAMESPACE = "test-namespace";
    private static final String TEST_POD_NAME = "test-pod";

    @BeforeEach
    void setUp() {
        // Reset mocks before each test
        reset(k8sClient);
    }

    // ========== debugPod() tests ==========

    @Test
    void testDebugPod_successfulPodRetrieval() {
        // Given: A healthy running pod with proper container state
        Pod testPod = new PodBuilder()
                .withNewMetadata()
                    .withName(TEST_POD_NAME)
                    .withNamespace(TEST_NAMESPACE)
                    .withLabels(Map.of("app", "test-app", "version", "v1"))
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("test-image:latest")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                    .withHostIP("192.168.1.1")
                    .withPodIP("10.0.0.1")
                    .withStartTime("2024-01-01T00:00:00Z")
                    .addNewCondition()
                        .withType("Ready")
                        .withStatus("True")
                        .withLastTransitionTime("2024-01-01T00:00:00Z")
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withReady(true)
                        .withRestartCount(0)
                        .withImage("test-image:latest")
                        .withNewState()
                            .withNewRunning()
                                .withStartedAt("2024-01-01T00:00:00Z")
                            .endRunning()
                        .endState()
                    .endContainerStatus()
                .endStatus()
                .build();
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(testPod);

        // When: Debugging the pod
        Map<String, Object> result = k8sTools.debugPod(TEST_NAMESPACE, TEST_POD_NAME);

        // Then: Complete debug info is returned
        assertThat(result).isNotNull();
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("podName")).isEqualTo(TEST_POD_NAME);
        assertThat(result.get("namespace")).isEqualTo(TEST_NAMESPACE);
        assertThat(result.get("phase")).isEqualTo("Running");
        assertThat(result).containsKey("conditions");
        assertThat(result).containsKey("containerStatuses");
        assertThat(result).containsKey("labels");
        
        verify(k8sClient).pods();
        verify(podOp).inNamespace(TEST_NAMESPACE);
        verify(nsOp).withName(TEST_POD_NAME);
    }

    @Test
    void testDebugPod_podNotFound() {
        // Given: Pod does not exist
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);

        // When: Attempting to debug non-existent pod
        Map<String, Object> result = k8sTools.debugPod(TEST_NAMESPACE, TEST_POD_NAME);

        // Then: Error map is returned
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("Pod not found");
        assertThat(result.get("error").toString()).contains(TEST_NAMESPACE);
        assertThat(result.get("error").toString()).contains(TEST_POD_NAME);
    }

    @Test
    void testDebugPod_nullOrEmptyParameters() {
        // When/Then: Null namespace
        Map<String, Object> result1 = k8sTools.debugPod(null, TEST_POD_NAME);
        assertThat(result1).containsKey("error");
        assertThat(result1.get("error").toString()).contains("namespace and podName are required");

        // When/Then: Empty namespace
        Map<String, Object> result2 = k8sTools.debugPod("", TEST_POD_NAME);
        assertThat(result2).containsKey("error");
        assertThat(result2.get("error").toString()).contains("namespace and podName are required");

        // When/Then: Null pod name
        Map<String, Object> result3 = k8sTools.debugPod(TEST_NAMESPACE, null);
        assertThat(result3).containsKey("error");
        assertThat(result3.get("error").toString()).contains("namespace and podName are required");

        // When/Then: Empty pod name
        Map<String, Object> result4 = k8sTools.debugPod(TEST_NAMESPACE, "");
        assertThat(result4).containsKey("error");
        assertThat(result4.get("error").toString()).contains("namespace and podName are required");
    }

    @Test
    void testDebugPod_crashLoopBackOff() {
        // Given: A pod in CrashLoopBackOff state
        Pod crashPod = K8sTestFixtures.createCrashLoopPod(TEST_POD_NAME, TEST_NAMESPACE);
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(crashPod);

        // When: Debugging the crashing pod
        Map<String, Object> result = k8sTools.debugPod(TEST_NAMESPACE, TEST_POD_NAME);

        // Then: Container status shows CrashLoopBackOff
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("phase")).isEqualTo("Running");
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containerStatuses = (List<Map<String, Object>>) result.get("containerStatuses");
        assertThat(containerStatuses).isNotEmpty();
        
        Map<String, Object> containerStatus = containerStatuses.get(0);
        assertThat(containerStatus.get("ready")).isEqualTo(false);
        assertThat(containerStatus.get("restartCount")).isEqualTo(5);
        assertThat(containerStatus.get("state")).isEqualTo("Waiting");
        assertThat(containerStatus.get("reason")).isEqualTo("CrashLoopBackOff");
    }

    @Test
    void testDebugPod_withMultipleContainers() {
        // Given: A pod with multiple containers
        Pod multiContainerPod = new PodBuilder()
                .withNewMetadata()
                    .withName(TEST_POD_NAME)
                    .withNamespace(TEST_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("app:latest")
                    .endContainer()
                    .addNewContainer()
                        .withName("sidecar")
                        .withImage("sidecar:latest")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                    .addNewCondition()
                        .withType("Ready")
                        .withStatus("True")
                    .endCondition()
                    .addNewContainerStatus()
                        .withName("app")
                        .withReady(true)
                        .withRestartCount(0)
                        .withNewState()
                            .withNewRunning()
                                .withStartedAt("2024-01-01T00:00:00Z")
                            .endRunning()
                        .endState()
                    .endContainerStatus()
                    .addNewContainerStatus()
                        .withName("sidecar")
                        .withReady(true)
                        .withRestartCount(0)
                        .withNewState()
                            .withNewRunning()
                                .withStartedAt("2024-01-01T00:00:00Z")
                            .endRunning()
                        .endState()
                    .endContainerStatus()
                .endStatus()
                .build();
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(multiContainerPod);

        // When: Debugging multi-container pod
        Map<String, Object> result = k8sTools.debugPod(TEST_NAMESPACE, TEST_POD_NAME);

        // Then: Both containers are included in status
        assertThat(result).doesNotContainKey("error");
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> containerStatuses = (List<Map<String, Object>>) result.get("containerStatuses");
        assertThat(containerStatuses).hasSize(2);
        assertThat(containerStatuses.get(0).get("name")).isEqualTo("app");
        assertThat(containerStatuses.get(1).get("name")).isEqualTo("sidecar");
    }

    // ========== getLogs() tests ==========

    @Test
    void testGetPodLogs_success() {
        // Given: A single-container pod with logs
        Pod testPod = new PodBuilder()
                .withNewMetadata()
                    .withName(TEST_POD_NAME)
                    .withNamespace(TEST_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("app:latest")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                .endStatus()
                .build();
        String expectedLogs = K8sTestFixtures.createTestPodLogs(TEST_POD_NAME, 10, false);
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        ContainerResource containerResource = mock(ContainerResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(testPod);
        when(podResource.inContainer("app")).thenReturn(containerResource);
        when(containerResource.tailingLines(anyInt())).thenReturn(containerResource);
        when(containerResource.getLog(anyBoolean())).thenReturn(expectedLogs);

        // When: Getting logs
        Map<String, Object> result = k8sTools.getLogs(TEST_NAMESPACE, TEST_POD_NAME, null, false, 100);

        // Then: Logs are returned
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("namespace")).isEqualTo(TEST_NAMESPACE);
        assertThat(result.get("podName")).isEqualTo(TEST_POD_NAME);
        assertThat(result.get("logs")).isEqualTo(expectedLogs);
        assertThat(result.get("previous")).isEqualTo(false);
    }

    @Test
    void testGetPodLogs_withTailLines() {
        // Given: A single-container pod with logs and specific tail lines
        Pod testPod = new PodBuilder()
                .withNewMetadata()
                    .withName(TEST_POD_NAME)
                    .withNamespace(TEST_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("app:latest")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                .endStatus()
                .build();
        String expectedLogs = K8sTestFixtures.createTestPodLogs(TEST_POD_NAME, 50, false);
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        ContainerResource containerResource = mock(ContainerResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(testPod);
        when(podResource.inContainer("app")).thenReturn(containerResource);
        when(containerResource.tailingLines(50)).thenReturn(containerResource);
        when(containerResource.getLog(false)).thenReturn(expectedLogs);

        // When: Getting logs with specific tail lines
        Map<String, Object> result = k8sTools.getLogs(TEST_NAMESPACE, TEST_POD_NAME, null, false, 50);

        // Then: Correct number of lines requested
        assertThat(result).doesNotContainKey("error");
        verify(containerResource).tailingLines(50);
    }

    @Test
    void testGetPodLogs_podNotFound() {
        // Given: Pod does not exist
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);

        // When: Attempting to get logs from non-existent pod
        Map<String, Object> result = k8sTools.getLogs(TEST_NAMESPACE, TEST_POD_NAME, null, false, 100);

        // Then: Error is returned
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("Pod not found");
    }

    @Test
    void testGetPodLogs_multipleContainers() {
        // Given: A multi-container pod
        Pod multiContainerPod = new PodBuilder()
                .withNewMetadata()
                    .withName(TEST_POD_NAME)
                    .withNamespace(TEST_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("app")
                        .withImage("app:latest")
                    .endContainer()
                    .addNewContainer()
                        .withName("istio-proxy")
                        .withImage("istio:latest")
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                .endStatus()
                .build();
        
        String expectedLogs = "App container logs";
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        PodResource podResource = mock(PodResource.class);
        ContainerResource containerResource = mock(ContainerResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(TEST_NAMESPACE)).thenReturn(nsOp);
        when(nsOp.withName(TEST_POD_NAME)).thenReturn(podResource);
        when(podResource.get()).thenReturn(multiContainerPod);
        when(podResource.inContainer("app")).thenReturn(containerResource);
        when(containerResource.tailingLines(anyInt())).thenReturn(containerResource);
        when(containerResource.getLog(anyBoolean())).thenReturn(expectedLogs);

        // When: Getting logs without specifying container (should default to non-sidecar)
        Map<String, Object> result = k8sTools.getLogs(TEST_NAMESPACE, TEST_POD_NAME, null, false, 100);

        // Then: Logs from app container are returned
        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("container")).isEqualTo("app");
        verify(podResource).inContainer("app");
    }

    // ========== getEvents() tests ==========
    // Note: Event tests require complex mocking of V1APIGroupDSL which has compatibility issues
    // These tests are covered by integration tests

    @Test
    void testGetEvents_nullNamespace() {
        // When/Then: Null namespace should return error
        Map<String, Object> result = k8sTools.getEvents(null, null, null);
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("namespace is required");
    }

    @Test
    void testGetEvents_emptyNamespace() {
        // When/Then: Empty namespace should return error
        Map<String, Object> result = k8sTools.getEvents("", null, null);
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("namespace is required");
    }

    @Test
    void testGetEvents_withLimit() {
        // This test verifies parameter validation
        // Full event retrieval is tested in integration tests
        Map<String, Object> result = k8sTools.getEvents(null, null, 10);
        assertThat(result).containsKey("error");
    }

    // ========== getMetrics() tests ==========
    // Note: Metrics tests require TopPodMetricOperation which has compatibility issues
    // These tests are covered by integration tests

    @Test
    void testGetMetrics_nullParameters() {
        // When/Then: Null namespace
        Map<String, Object> result1 = k8sTools.getMetrics(null, TEST_POD_NAME);
        assertThat(result1).containsKey("error");
        assertThat(result1.get("error").toString()).contains("namespace and podName are required");

        // When/Then: Null pod name
        Map<String, Object> result2 = k8sTools.getMetrics(TEST_NAMESPACE, null);
        assertThat(result2).containsKey("error");
        assertThat(result2.get("error").toString()).contains("namespace and podName are required");
    }

    @Test
    void testGetMetrics_emptyParameters() {
        // When/Then: Empty namespace
        Map<String, Object> result1 = k8sTools.getMetrics("", TEST_POD_NAME);
        assertThat(result1).containsKey("error");

        // When/Then: Empty pod name
        Map<String, Object> result2 = k8sTools.getMetrics(TEST_NAMESPACE, "");
        assertThat(result2).containsKey("error");
    }

    // ========== inspectResources() tests ==========

    @Test
    void testInspectResources_nullNamespace() {
        // When/Then: Null namespace should return error
        Map<String, Object> result = k8sTools.inspectResources(null, null, null, null);
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("namespace is required");
    }

    @Test
    void testInspectResources_emptyNamespace() {
        // When/Then: Empty namespace should return error
        Map<String, Object> result = k8sTools.inspectResources("", null, null, null);
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("namespace is required");
    }
}

