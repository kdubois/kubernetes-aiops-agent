package dev.kevindubois.rollout.agent.k8s;

import dev.kevindubois.rollout.agent.fixtures.K8sTestFixtures;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for K8sTools class
 */
@QuarkusTest
class K8sToolsTest {

    @Inject
    K8sTools k8sTools;

    @InjectMock
    KubernetesClient k8sClient;

    private MixedOperation<Pod, PodList, PodResource> podOp;
    private NonNamespaceOperation<Pod, PodList, PodResource> nsOp;
    private PodResource podResource;

    @BeforeEach
    void setUp() {
        Mockito.reset(k8sClient);
        
        // Setup common mocks
        podOp = mock(MixedOperation.class);
        nsOp = mock(NonNamespaceOperation.class);
        podResource = mock(PodResource.class);
        
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(anyString())).thenReturn(nsOp);
    }

    @Test
    void testDebugPod_Success() {
        // Given
        String namespace = "default";
        String podName = "test-pod";
        Pod pod = K8sTestFixtures.createTestPod(podName, namespace);
        
        when(nsOp.withName(podName)).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);
        
        // When
        Map<String, Object> result = k8sTools.debugPod(namespace, podName);
        
        // Then
        assertNotNull(result);
        assertFalse(result.containsKey("error"), "Should not contain error");
        assertEquals(podName, result.get("podName"));
        assertEquals(namespace, result.get("namespace"));
        assertEquals("Running", result.get("phase"));
        assertNotNull(result.get("conditions"));
        assertNotNull(result.get("containerStatuses"));
    }

    @Test
    void testDebugPod_PodNotFound() {
        // Given
        String namespace = "default";
        String podName = "missing-pod";
        
        when(nsOp.withName(podName)).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);
        
        // When
        Map<String, Object> result = k8sTools.debugPod(namespace, podName);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Pod not found"));
    }

    @Test
    void testDebugPod_EmptyNamespace() {
        // When
        Map<String, Object> result = k8sTools.debugPod("", "test-pod");
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testDebugPod_EmptyPodName() {
        // When
        Map<String, Object> result = k8sTools.debugPod("default", "");
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testGetEvents_EmptyNamespace() {
        // When
        Map<String, Object> result = k8sTools.getEvents("", null, 50);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }
    
    @Test
    void testGetEvents_NullNamespace() {
        // When
        Map<String, Object> result = k8sTools.getEvents(null, null, 50);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    @Test
    void testGetLogs_Success() {
        // Given
        String namespace = "default";
        String podName = "test-pod";
        String logs = "Test log line 1\nTest log line 2";
        Pod pod = K8sTestFixtures.createTestPod(podName, namespace);
        
        ContainerResource containerResource = mock(ContainerResource.class);
        
        when(nsOp.withName(podName)).thenReturn(podResource);
        when(podResource.get()).thenReturn(pod);
        when(podResource.inContainer(anyString())).thenReturn(containerResource);
        when(containerResource.tailingLines(anyInt())).thenReturn(containerResource);
        when(containerResource.getLog(anyBoolean())).thenReturn(logs);
        
        // When
        Map<String, Object> result = k8sTools.getLogs(namespace, podName, null, false, 200);
        
        // Then
        assertNotNull(result);
        assertEquals(namespace, result.get("namespace"));
        assertEquals(podName, result.get("podName"));
        assertEquals(logs, result.get("logs"));
    }

    @Test
    void testGetLogs_PodNotFound() {
        // Given
        String namespace = "default";
        String podName = "missing-pod";
        
        when(nsOp.withName(podName)).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);
        
        // When
        Map<String, Object> result = k8sTools.getLogs(namespace, podName, null, false, 200);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Pod not found"));
    }

    @Test
    void testGetLogs_EmptyParameters() {
        // When
        Map<String, Object> result = k8sTools.getLogs("", "", null, false, 200);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testGetMetrics_PodNotFound() {
        // Given
        String namespace = "default";
        String podName = "missing-pod";
        
        when(nsOp.withName(podName)).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);
        
        // When
        Map<String, Object> result = k8sTools.getMetrics(namespace, podName);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Pod not found"));
    }

    @Test
    void testGetMetrics_EmptyParameters() {
        // When
        Map<String, Object> result = k8sTools.getMetrics("", "");
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testInspectResources_Pods() {
        // Given
        String namespace = "default";
        Pod pod1 = K8sTestFixtures.createTestPod("pod-1", namespace);
        Pod pod2 = K8sTestFixtures.createTestPod("pod-2", namespace);
        PodList podList = new PodList();
        podList.setItems(List.of(pod1, pod2));
        
        when(nsOp.list()).thenReturn(podList);
        
        // When
        Map<String, Object> result = k8sTools.inspectResources(namespace, "pods", null, null);
        
        // Then
        assertNotNull(result);
        assertEquals(namespace, result.get("namespace"));
        assertTrue(result.containsKey("pods"));
    }

    @Test
    void testInspectResources_WithLabelSelector() {
        // Given
        String namespace = "default";
        String labelSelector = "role=canary";
        Pod canaryPod = K8sTestFixtures.createTestPod("canary-pod", namespace);
        canaryPod.getMetadata().setLabels(Map.of("role", "canary"));
        PodList podList = new PodList();
        podList.setItems(List.of(canaryPod));
        
        FilterWatchListDeletable<Pod, PodList, PodResource> labelOp = mock(FilterWatchListDeletable.class);
        when(nsOp.withLabels(anyMap())).thenReturn(labelOp);
        when(labelOp.list()).thenReturn(podList);
        
        // When
        Map<String, Object> result = k8sTools.inspectResources(namespace, "pods", null, labelSelector);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("pods"));
    }

    @Test
    void testInspectResources_EmptyNamespace() {
        // When
        Map<String, Object> result = k8sTools.inspectResources("", null, null, null);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testFetchApplicationMetrics_PodNotFound() {
        // Given
        String namespace = "default";
        String podName = "missing-pod";
        
        when(nsOp.withName(podName)).thenReturn(podResource);
        when(podResource.get()).thenReturn(null);
        
        // When
        Map<String, Object> result = k8sTools.fetchApplicationMetrics(namespace, podName, null, null);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Pod not found"));
    }

    @Test
    void testFetchApplicationMetrics_EmptyParameters() {
        // When
        Map<String, Object> result = k8sTools.fetchApplicationMetrics("", "", null, null);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testGetCanaryDiagnostics_Success() {
        // Given
        String namespace = "default";
        Pod stablePod = K8sTestFixtures.createTestPod("stable-pod", namespace);
        stablePod.getMetadata().setLabels(Map.of("role", "stable"));
        Pod canaryPod = K8sTestFixtures.createTestPod("canary-pod", namespace);
        canaryPod.getMetadata().setLabels(Map.of("role", "canary"));
        
        PodList stablePodList = new PodList();
        stablePodList.setItems(List.of(stablePod));
        PodList canaryPodList = new PodList();
        canaryPodList.setItems(List.of(canaryPod));
        
        FilterWatchListDeletable<Pod, PodList, PodResource> stableLabelOp = mock(FilterWatchListDeletable.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> canaryLabelOp = mock(FilterWatchListDeletable.class);
        
        when(nsOp.withLabels(Map.of("role", "stable"))).thenReturn(stableLabelOp);
        when(stableLabelOp.list()).thenReturn(stablePodList);
        
        when(nsOp.withLabels(Map.of("role", "canary"))).thenReturn(canaryLabelOp);
        when(canaryLabelOp.list()).thenReturn(canaryPodList);
        
        // Mock pod resource for logs
        PodResource stablePodResource = mock(PodResource.class);
        PodResource canaryPodResource = mock(PodResource.class);
        
        when(nsOp.withName("stable-pod")).thenReturn(stablePodResource);
        when(stablePodResource.get()).thenReturn(stablePod);
        when(stablePodResource.tailingLines(anyInt())).thenReturn(stablePodResource);
        when(stablePodResource.getLog(anyBoolean())).thenReturn("Stable logs");
        
        when(nsOp.withName("canary-pod")).thenReturn(canaryPodResource);
        when(canaryPodResource.get()).thenReturn(canaryPod);
        when(canaryPodResource.tailingLines(anyInt())).thenReturn(canaryPodResource);
        when(canaryPodResource.getLog(anyBoolean())).thenReturn("Canary logs");
        
        // When
        Map<String, Object> result = k8sTools.getCanaryDiagnostics(namespace, "app", 200);
        
        // Then
        assertNotNull(result);
        assertEquals(namespace, result.get("namespace"));
        assertTrue(result.containsKey("stable"));
        assertTrue(result.containsKey("canary"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> stableInfo = (Map<String, Object>) result.get("stable");
        assertNotNull(stableInfo);
        assertEquals("stable-pod", stableInfo.get("podName"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> canaryInfo = (Map<String, Object>) result.get("canary");
        assertNotNull(canaryInfo);
        assertEquals("canary-pod", canaryInfo.get("podName"));
    }

    @Test
    void testGetCanaryDiagnostics_NoStablePods() {
        // Given
        String namespace = "default";
        Pod canaryPod = K8sTestFixtures.createTestPod("canary-pod", namespace);
        canaryPod.getMetadata().setLabels(Map.of("role", "canary"));
        
        PodList emptyStablePodList = new PodList();
        emptyStablePodList.setItems(List.of());
        PodList canaryPodList = new PodList();
        canaryPodList.setItems(List.of(canaryPod));
        
        FilterWatchListDeletable<Pod, PodList, PodResource> stableLabelOp = mock(FilterWatchListDeletable.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> canaryLabelOp = mock(FilterWatchListDeletable.class);
        
        when(nsOp.withLabels(Map.of("role", "stable"))).thenReturn(stableLabelOp);
        when(stableLabelOp.list()).thenReturn(emptyStablePodList);
        
        when(nsOp.withLabels(Map.of("role", "canary"))).thenReturn(canaryLabelOp);
        when(canaryLabelOp.list()).thenReturn(canaryPodList);
        
        PodResource canaryPodResource = mock(PodResource.class);
        when(nsOp.withName("canary-pod")).thenReturn(canaryPodResource);
        when(canaryPodResource.get()).thenReturn(canaryPod);
        when(canaryPodResource.tailingLines(anyInt())).thenReturn(canaryPodResource);
        when(canaryPodResource.getLog(anyBoolean())).thenReturn("Canary logs");
        
        // When
        Map<String, Object> result = k8sTools.getCanaryDiagnostics(namespace, "app", 200);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("stable"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> stableInfo = (Map<String, Object>) result.get("stable");
        assertTrue(stableInfo.containsKey("error"));
        assertTrue(stableInfo.get("error").toString().contains("No stable pods found"));
    }

    @Test
    void testGetCanaryDiagnostics_NoCanaryPods() {
        // Given
        String namespace = "default";
        Pod stablePod = K8sTestFixtures.createTestPod("stable-pod", namespace);
        stablePod.getMetadata().setLabels(Map.of("role", "stable"));
        
        PodList stablePodList = new PodList();
        stablePodList.setItems(List.of(stablePod));
        PodList emptyCanaryPodList = new PodList();
        emptyCanaryPodList.setItems(List.of());
        
        FilterWatchListDeletable<Pod, PodList, PodResource> stableLabelOp = mock(FilterWatchListDeletable.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> canaryLabelOp = mock(FilterWatchListDeletable.class);
        
        when(nsOp.withLabels(Map.of("role", "stable"))).thenReturn(stableLabelOp);
        when(stableLabelOp.list()).thenReturn(stablePodList);
        
        when(nsOp.withLabels(Map.of("role", "canary"))).thenReturn(canaryLabelOp);
        when(canaryLabelOp.list()).thenReturn(emptyCanaryPodList);
        
        PodResource stablePodResource = mock(PodResource.class);
        when(nsOp.withName("stable-pod")).thenReturn(stablePodResource);
        when(stablePodResource.get()).thenReturn(stablePod);
        when(stablePodResource.tailingLines(anyInt())).thenReturn(stablePodResource);
        when(stablePodResource.getLog(anyBoolean())).thenReturn("Stable logs");
        
        // When
        Map<String, Object> result = k8sTools.getCanaryDiagnostics(namespace, "app", 200);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("canary"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> canaryInfo = (Map<String, Object>) result.get("canary");
        assertTrue(canaryInfo.containsKey("error"));
        assertTrue(canaryInfo.get("error").toString().contains("No canary pods found"));
    }

    @Test
    void testGetCanaryDiagnostics_EmptyNamespace() {
        // When
        Map<String, Object> result = k8sTools.getCanaryDiagnostics("", "app", 200);
        
        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("required"));
    }

    @Test
    void testGetCanaryDiagnostics_DefaultTailLines() {
        // Given
        String namespace = "default";
        Pod stablePod = K8sTestFixtures.createTestPod("stable-pod", namespace);
        stablePod.getMetadata().setLabels(Map.of("role", "stable"));
        
        PodList stablePodList = new PodList();
        stablePodList.setItems(List.of(stablePod));
        PodList emptyCanaryPodList = new PodList();
        emptyCanaryPodList.setItems(List.of());
        
        FilterWatchListDeletable<Pod, PodList, PodResource> stableLabelOp = mock(FilterWatchListDeletable.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> canaryLabelOp = mock(FilterWatchListDeletable.class);
        
        when(nsOp.withLabels(Map.of("role", "stable"))).thenReturn(stableLabelOp);
        when(stableLabelOp.list()).thenReturn(stablePodList);
        
        when(nsOp.withLabels(Map.of("role", "canary"))).thenReturn(canaryLabelOp);
        when(canaryLabelOp.list()).thenReturn(emptyCanaryPodList);
        
        PodResource stablePodResource = mock(PodResource.class);
        when(nsOp.withName("stable-pod")).thenReturn(stablePodResource);
        when(stablePodResource.get()).thenReturn(stablePod);
        when(stablePodResource.tailingLines(anyInt())).thenReturn(stablePodResource);
        when(stablePodResource.getLog(anyBoolean())).thenReturn("Stable logs");
        
        // When - passing null for tailLines to test default value
        Map<String, Object> result = k8sTools.getCanaryDiagnostics(namespace, "app", null);
        
        // Then
        assertNotNull(result);
        assertEquals(namespace, result.get("namespace"));
    }
}

