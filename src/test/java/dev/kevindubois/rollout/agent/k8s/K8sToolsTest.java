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

@QuarkusTest
class K8sToolsTest {

    @Inject
    K8sTools k8sTools;

    @InjectMock
    KubernetesClient k8sClient;

    private MixedOperation<Pod, PodList, PodResource> podOp;
    private NonNamespaceOperation<Pod, PodList, PodResource> nsOp;

    @BeforeEach
    void setUp() {
        Mockito.reset(k8sClient);
        podOp = mock(MixedOperation.class);
        nsOp = mock(NonNamespaceOperation.class);
        when(k8sClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(anyString())).thenReturn(nsOp);
    }

    @Test
    void testGetCanaryDiagnostics_Success() {
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", 200);

        assertNotNull(result);
        assertFalse(result.hasError());
        assertNotNull(result.stable());
        assertNotNull(result.canary());
        assertEquals("stable-pod", result.stable().get("podName"));
        assertEquals("canary-pod", result.canary().get("podName"));
    }

    @Test
    void testGetCanaryDiagnostics_NoStablePods() {
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", 200);

        assertNotNull(result);
        assertNotNull(result.stable());
        assertTrue(result.stable().containsKey("error"));
    }

    @Test
    void testGetCanaryDiagnostics_NoCanaryPods() {
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", 200);

        assertNotNull(result);
        assertNotNull(result.canary());
        assertTrue(result.canary().containsKey("error"));
    }

    @Test
    void testGetCanaryDiagnostics_EmptyNamespace() {
        PodDataResult result = k8sTools.getCanaryDiagnostics("", "app", 200);
        assertNotNull(result);
        assertTrue(result.hasError());
        assertTrue(result.error().contains("required"));
    }

    @Test
    void testGetRolloutStatus_Success() {
        String namespace = "default";
        mockRollouts(namespace, K8sTestFixtures.createTestRollout(
                "demo-rollout", namespace, "Progressing", 2, 30));
        mockPods(namespace, 2, 3);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null);

        assertFalse(result.hasError());
        assertEquals("demo-rollout", result.rolloutName());
        assertEquals("Progressing", result.phase());
        assertEquals(2, result.currentStepIndex());
        assertEquals(30, result.canaryWeight());
        assertEquals(70, result.stableWeight());
        assertEquals(2, result.stablePodCount());
        assertEquals(3, result.canaryPodCount());
    }

    @Test
    void testGetRolloutStatus_FallbackStepWeights() {
        String namespace = "default";
        // No live weights reported: canaryWeight -1 makes the fixture omit them
        mockRollouts(namespace, K8sTestFixtures.createTestRollout(
                "demo-rollout", namespace, "Progressing", 4, -1));
        mockPods(namespace, 1, 1);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null);

        assertFalse(result.hasError());
        assertEquals(60, result.canaryWeight());
        assertEquals(40, result.stableWeight());
    }

    @Test
    void testGetRolloutStatus_DegradedZeroCanaryWeight() {
        String namespace = "default";
        mockRollouts(namespace, K8sTestFixtures.createTestRollout(
                "demo-rollout", namespace, "Degraded", 2, 60));
        mockPods(namespace, 1, 1);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null);

        assertFalse(result.hasError());
        assertEquals(0, result.canaryWeight());
    }

    @Test
    void testGetRolloutStatus_NoRollouts() {
        String namespace = "default";
        mockRollouts(namespace, null);
        mockPods(namespace, 0, 0);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null);

        assertTrue(result.hasError());
        assertTrue(result.error().contains("No Rollout found"));
    }

    @Test
    void testGetRolloutStatus_EmptyNamespace() {
        RolloutStatusData result = k8sTools.getRolloutStatus("", null);
        assertNotNull(result);
        assertTrue(result.hasError());
        assertTrue(result.error().contains("required"));
    }

    @SuppressWarnings("unchecked")
    private void mockRollouts(String namespace, Rollout rollout) {
        MixedOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutOp = mock(MixedOperation.class);
        NonNamespaceOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutNsOp =
                mock(NonNamespaceOperation.class);
        when(k8sClient.resources(Rollout.class)).thenReturn(rolloutOp);
        when(rolloutOp.inNamespace(namespace)).thenReturn(rolloutNsOp);

        KubernetesResourceList<Rollout> rolloutList = mock(KubernetesResourceList.class);
        when(rolloutNsOp.list()).thenReturn(rolloutList);
        when(rolloutList.getItems()).thenReturn(rollout != null ? List.of(rollout) : List.of());
    }

    private void mockPods(String namespace, int stableCount, int canaryCount) {
        PodList stablePodList = new PodList();
        for (int i = 0; i < stableCount; i++) {
            stablePodList.getItems().add(K8sTestFixtures.createTestPod("stable-" + i, namespace));
        }
        PodList canaryPodList = new PodList();
        for (int i = 0; i < canaryCount; i++) {
            canaryPodList.getItems().add(K8sTestFixtures.createTestPod("canary-" + i, namespace));
        }

        FilterWatchListDeletable<Pod, PodList, PodResource> stableLabelOp = mock(FilterWatchListDeletable.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> canaryLabelOp = mock(FilterWatchListDeletable.class);

        when(nsOp.withLabels(Map.of("role", "stable"))).thenReturn(stableLabelOp);
        when(stableLabelOp.list()).thenReturn(stablePodList);
        when(nsOp.withLabels(Map.of("role", "canary"))).thenReturn(canaryLabelOp);
        when(canaryLabelOp.list()).thenReturn(canaryPodList);
    }

    @Test
    void testGetCanaryDiagnostics_DefaultTailLines() {
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", null);

        assertNotNull(result);
        assertFalse(result.hasError());
    }
}
