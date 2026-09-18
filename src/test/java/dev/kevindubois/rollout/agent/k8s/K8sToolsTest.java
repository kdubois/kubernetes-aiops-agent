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
