package dev.kevindubois.rollout.agent.k8s;

import dev.kevindubois.rollout.agent.fixtures.K8sTestFixtures;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", 200, null, null);

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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", 200, null, null);

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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", 200, null, null);

        assertNotNull(result);
        assertNotNull(result.canary());
        assertTrue(result.canary().containsKey("error"));
    }

    @Test
    void testGetCanaryDiagnostics_EmptyNamespace() {
        PodDataResult result = k8sTools.getCanaryDiagnostics("", "app", 200, null, null);
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

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null, null, null);

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
    void testGetRolloutStatus_NoLiveWeightsReportsAbsence() {
        String namespace = "default";
        // No live weights reported: canaryWeight -1 makes the fixture omit them
        mockRollouts(namespace, K8sTestFixtures.createTestRollout(
                "demo-rollout", namespace, "Progressing", 4, -1));
        mockPods(namespace, 1, 1);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null, null, null);

        assertFalse(result.hasError());
        assertNull(result.canaryWeight(), "no live weight published → absence, not an invented percentage");
        assertNull(result.stableWeight());
    }

    @Test
    void testGetRolloutStatus_DegradedZeroCanaryWeight() {
        String namespace = "default";
        mockRollouts(namespace, K8sTestFixtures.createTestRollout(
                "demo-rollout", namespace, "Degraded", 2, 60));
        mockPods(namespace, 1, 1);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null, null, null);

        assertFalse(result.hasError());
        assertEquals(0, result.canaryWeight());
    }

    @Test
    void testGetRolloutStatus_NoRollouts() {
        String namespace = "default";
        mockRollouts(namespace, null);
        mockPods(namespace, 0, 0);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null, null, null);

        assertTrue(result.hasError());
        assertTrue(result.error().contains("No Rollout found"));
    }

    @Test
    void testGetRolloutStatus_EmptyNamespace() {
        RolloutStatusData result = k8sTools.getRolloutStatus("", null, null, null);
        assertNotNull(result);
        assertTrue(result.hasError());
        assertTrue(result.error().contains("required"));
    }

    @Test
    void testPauseRollout_Success() {
        String namespace = "default";
        Rollout rollout = K8sTestFixtures.createTestRollout("demo-rollout", namespace, "Progressing", 2, 30);
        var rolloutNsOp = rolloutNsOpFor(namespace);

        KubernetesResourceList<Rollout> rolloutList = mock(KubernetesResourceList.class);
        when(rolloutNsOp.list()).thenReturn(rolloutList);
        when(rolloutList.getItems()).thenReturn(List.of(rollout));

        Resource<Rollout> namedResource = mock(Resource.class);
        when(rolloutNsOp.withName("demo-rollout")).thenReturn(namedResource);
        when(namedResource.patch(any(PatchContext.class), anyString())).thenReturn(rollout);

        String paused = k8sTools.pauseRollout(namespace, null);

        assertEquals("demo-rollout", paused);
        verify(namedResource).patch(any(PatchContext.class), eq("{\"spec\":{\"paused\":true}}"));
    }

    @Test
    void testPauseRollout_PausesTheNamedRolloutAmongSeveral() {
        String namespace = "default";
        Rollout target = K8sTestFixtures.createTestRollout("demo-rollout", namespace, "Progressing", 2, 30);
        var rolloutNsOp = rolloutNsOpFor(namespace);

        Resource<Rollout> namedResource = mock(Resource.class);
        when(rolloutNsOp.withName("demo-rollout")).thenReturn(namedResource);
        when(namedResource.get()).thenReturn(target);
        when(namedResource.patch(any(PatchContext.class), anyString())).thenReturn(target);

        String paused = k8sTools.pauseRollout(namespace, "demo-rollout");

        assertEquals("demo-rollout", paused);
        verify(namedResource).patch(any(PatchContext.class), eq("{\"spec\":{\"paused\":true}}"));
    }

    @Test
    void testPauseRollout_AlreadyPausedSkipsThePatch() {
        String namespace = "default";
        Rollout rollout = K8sTestFixtures.createTestRollout("demo-rollout", namespace, "Progressing", 2, 30);
        RolloutSpec spec = new RolloutSpec();
        spec.setPaused(true);
        rollout.setSpec(spec);
        var rolloutNsOp = rolloutNsOpFor(namespace);

        KubernetesResourceList<Rollout> rolloutList = mock(KubernetesResourceList.class);
        when(rolloutNsOp.list()).thenReturn(rolloutList);
        when(rolloutList.getItems()).thenReturn(List.of(rollout));

        Resource<Rollout> namedResource = mock(Resource.class);
        when(rolloutNsOp.withName("demo-rollout")).thenReturn(namedResource);

        String paused = k8sTools.pauseRollout(namespace, null);

        assertEquals("demo-rollout", paused);
        verify(namedResource, never()).patch(any(PatchContext.class), anyString());
    }

    @Test
    void testPauseRollout_PatchFails() {
        String namespace = "default";
        Rollout rollout = K8sTestFixtures.createTestRollout("demo-rollout", namespace, "Progressing", 2, 30);
        var rolloutNsOp = rolloutNsOpFor(namespace);

        KubernetesResourceList<Rollout> rolloutList = mock(KubernetesResourceList.class);
        when(rolloutNsOp.list()).thenReturn(rolloutList);
        when(rolloutList.getItems()).thenReturn(List.of(rollout));

        Resource<Rollout> namedResource = mock(Resource.class);
        when(rolloutNsOp.withName("demo-rollout")).thenReturn(namedResource);
        when(namedResource.patch(any(PatchContext.class), anyString()))
                .thenThrow(new RuntimeException("403 Forbidden"));

        String paused = k8sTools.pauseRollout(namespace, null);

        assertNull(paused);
    }

    @Test
    void testPauseRollout_NoRollouts() {
        String namespace = "default";
        var rolloutNsOp = rolloutNsOpFor(namespace);

        KubernetesResourceList<Rollout> rolloutList = mock(KubernetesResourceList.class);
        when(rolloutNsOp.list()).thenReturn(rolloutList);
        when(rolloutList.getItems()).thenReturn(List.of());

        String paused = k8sTools.pauseRollout(namespace, null);

        assertNull(paused);
    }

    @Test
    void testPauseRollout_EmptyNamespace() {
        assertNull(k8sTools.pauseRollout("", null));
    }

    @Test
    void testGetRolloutStatus_ByName() {
        String namespace = "default";
        Rollout rollout = K8sTestFixtures.createTestRollout("demo-rollout", namespace, "Progressing", 2, 30);
        Resource<Rollout> namedResource = mock(Resource.class);
        when(rolloutNsOpFor(namespace).withName("demo-rollout")).thenReturn(namedResource);
        when(namedResource.get()).thenReturn(rollout);
        mockPods(namespace, 1, 1);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, "demo-rollout", null, null);

        assertFalse(result.hasError());
        assertEquals("demo-rollout", result.rolloutName());
        assertEquals(30, result.canaryWeight());
    }

    @Test
    void testGetRolloutStatus_ByNameNotFound() {
        String namespace = "default";
        Resource<Rollout> namedResource = mock(Resource.class);
        when(rolloutNsOpFor(namespace).withName("missing-rollout")).thenReturn(namedResource);
        when(namedResource.get()).thenReturn(null);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, "missing-rollout", null, null);

        assertTrue(result.hasError());
        assertTrue(result.error().contains("missing-rollout"));
        assertTrue(result.error().contains(namespace));
    }

    @Test
    void testGetRolloutStatus_HealthyConvergedZeroCanaryWeight() {
        String namespace = "default";
        Rollout rollout = K8sTestFixtures.createTestRollout("demo-rollout", namespace, "Healthy", 6, 60);
        // A finished rollout reports the same replica-set hash for stable and canary
        rollout.getStatus().setCanaryRS(rollout.getStatus().getStableRS());
        mockRollouts(namespace, rollout);
        mockPods(namespace, 2, 2);

        RolloutStatusData result = k8sTools.getRolloutStatus(namespace, null, null, null);

        assertFalse(result.hasError());
        assertEquals("Healthy", result.phase());
        assertEquals(0, result.canaryWeight());
        assertEquals(100, result.stableWeight());
    }

    @SuppressWarnings("unchecked")
    private NonNamespaceOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutNsOpFor(String namespace) {
        MixedOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutOp = mock(MixedOperation.class);
        NonNamespaceOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutNsOp =
                mock(NonNamespaceOperation.class);
        when(k8sClient.resources(Rollout.class)).thenReturn(rolloutOp);
        when(rolloutOp.inNamespace(namespace)).thenReturn(rolloutNsOp);
        return rolloutNsOp;
    }

    @SuppressWarnings("unchecked")
    private void mockRollouts(String namespace, Rollout rollout) {
        NonNamespaceOperation<Rollout, KubernetesResourceList<Rollout>, Resource<Rollout>> rolloutNsOp =
                rolloutNsOpFor(namespace);

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
    void testGetCanaryDiagnostics_CustomSelectors() {
        String namespace = "default";
        Pod stablePod = K8sTestFixtures.createTestPod("stable-pod", namespace);
        Pod canaryPod = K8sTestFixtures.createTestPod("canary-pod", namespace);

        PodList stablePodList = new PodList();
        stablePodList.setItems(List.of(stablePod));
        PodList canaryPodList = new PodList();
        canaryPodList.setItems(List.of(canaryPod));

        FilterWatchListDeletable<Pod, PodList, PodResource> stableLabelOp = mock(FilterWatchListDeletable.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> canaryLabelOp = mock(FilterWatchListDeletable.class);

        when(nsOp.withLabels(Map.of("app", "demo", "tier", "backend"))).thenReturn(stableLabelOp);
        when(stableLabelOp.list()).thenReturn(stablePodList);
        when(nsOp.withLabels(Map.of("app", "demo", "tier", "frontend"))).thenReturn(canaryLabelOp);
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, null, 200,
                "app=demo,tier=backend", "app=demo,tier=frontend");

        assertNotNull(result);
        assertFalse(result.hasError());
        assertEquals("stable-pod", result.stable().get("podName"));
        assertEquals("canary-pod", result.canary().get("podName"));
        verify(nsOp).withLabels(Map.of("app", "demo", "tier", "backend"));
        verify(nsOp).withLabels(Map.of("app", "demo", "tier", "frontend"));
    }

    @Test
    void testParseLabels() {
        assertEquals(Map.of("role", "canary"), K8sTools.parseLabels("role=canary"));
        assertEquals(Map.of("app", "demo", "tier", "backend"), K8sTools.parseLabels("app=demo,tier=backend"));
        assertEquals(Map.of("app", "demo", "tier", "backend"), K8sTools.parseLabels(" app=demo , tier=backend "));
        assertEquals(Map.of(), K8sTools.parseLabels(null));
        assertEquals(Map.of(), K8sTools.parseLabels("  "));
        // malformed segments are skipped, valid ones survive
        assertEquals(Map.of("role", "canary"), K8sTools.parseLabels("novalue,=missing,role=canary,empty="));
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

        PodDataResult result = k8sTools.getCanaryDiagnostics(namespace, "app", null, null, null);

        assertNotNull(result);
        assertFalse(result.hasError());
    }

    @Test
    void sampleTooThinRequiresScrapedMetricsBelowTheFloor() {
        // a scraped canary with a small sample is thin
        assertTrue(K8sTools.sampleTooThin(Map.of("totalRequests", 42.0, "podCount", 1), 500));
        // a grown sample is not
        assertFalse(K8sTools.sampleTooThin(Map.of("totalRequests", 900.0, "podCount", 1), 500));
        // a scrape error must not trigger waiting — the caller reports it
        assertFalse(K8sTools.sampleTooThin(Map.of("error", "No pods found"), 500));
        // floor disabled
        assertFalse(K8sTools.sampleTooThin(Map.of("totalRequests", 42.0), 0));
    }

    @Test
    void windowDeltasMeasureTheIntervalBetweenScrapes() {
        Map<String, Object> first = Map.of(
                "totalRequests", 100.0, "successfulRequests", 99.0,
                "latencyCount", 100.0, "latencySumSeconds", 5.0);
        Map<String, Object> next = new HashMap<>(Map.of(
                "totalRequests", 412.0, "successfulRequests", 408.0,
                "latencyCount", 412.0, "latencySumSeconds", 21.0));

        K8sTools.addWindowDeltas(first, next, 10);

        assertEquals(312.0, next.get("windowRequests"));
        assertEquals(3.0, next.get("windowErrorRequests"));
        assertEquals(0.96, (Double) next.get("windowErrorRate"), 0.01);
        // (21s - 5s) / (412 - 100) requests = 51.3ms mean over the window
        assertEquals(51.28, (Double) next.get("windowMeanLatencyMs"), 0.01);
    }

    @Test
    void windowLatencyReportsTheSlowestPodNotJustTheFleetMean() {
        // The canary scaled up between scrapes: five fast pods dominate the
        // request count while the one older pod is now 10x slower. The fleet
        // mean hides it; the worst pod is the regression.
        Map<String, Object> firstSlow = podSample("slow-pod", 100, 3.0);
        Map<String, Object> nextSlow = podSample("slow-pod", 200, 33.0);
        Map<String, Object> firstFresh = podSample("fresh-pod", 100, 3.0);
        Map<String, Object> nextFresh = podSample("fresh-pod", 500, 15.0);

        Map<String, Object> first = new HashMap<>(Map.of(
                "totalRequests", 200.0, "successfulRequests", 200.0,
                "latencyCount", 200.0, "latencySumSeconds", 6.0,
                "perPodMetrics", List.of(firstSlow, firstFresh)));
        Map<String, Object> next = new HashMap<>(Map.of(
                "totalRequests", 700.0, "successfulRequests", 700.0,
                "latencyCount", 700.0, "latencySumSeconds", 48.0,
                "perPodMetrics", List.of(nextSlow, nextFresh)));

        K8sTools.addWindowDeltas(first, next, 10);

        // fleet mean: (48 - 6) / (700 - 200) = 84ms
        assertEquals(84.0, (Double) next.get("windowMeanLatencyMs"), 0.01);
        // worst pod: (33 - 3) / (200 - 100) = 300ms
        assertEquals(300.0, (Double) next.get("windowWorstPodMeanLatencyMs"), 0.01);
        assertEquals("slow-pod", next.get("windowWorstPod"));
    }

    @Test
    void windowWorstPodSkipsPodsMissingFromTheFirstScrape() {
        Map<String, Object> first = new HashMap<>(Map.of(
                "totalRequests", 100.0, "successfulRequests", 100.0,
                "latencyCount", 100.0, "latencySumSeconds", 3.0,
                "perPodMetrics", List.of(podSample("existing-pod", 100, 3.0))));
        Map<String, Object> next = new HashMap<>(Map.of(
                "totalRequests", 300.0, "successfulRequests", 300.0,
                "latencyCount", 300.0, "latencySumSeconds", 7.0,
                "perPodMetrics", List.of(
                        podSample("existing-pod", 200, 5.0),
                        podSample("brand-new-pod", 100, 2.0))));

        K8sTools.addWindowDeltas(first, next, 10);

        // existing pod: (5 - 3) / 100 = 20ms; the new pod has no baseline
        assertEquals(20.0, (Double) next.get("windowWorstPodMeanLatencyMs"), 0.01);
        assertEquals("existing-pod", next.get("windowWorstPod"));
    }

    private static Map<String, Object> podSample(String podName, double latencyCount, double latencySumSeconds) {
        Map<String, Object> sample = new HashMap<>();
        sample.put("podName", podName);
        sample.put("latencyCount", latencyCount);
        sample.put("latencySumSeconds", latencySumSeconds);
        return sample;
    }

    @Test
    void counterResetsAndScrapeErrorsProduceNoWindowFields() {
        // pod restarted between the scrapes: counters went backwards
        Map<String, Object> reset = new HashMap<>(Map.of("totalRequests", 5.0, "successfulRequests", 5.0));
        K8sTools.addWindowDeltas(Map.of("totalRequests", 100.0, "successfulRequests", 99.0), reset, 10);
        assertFalse(reset.containsKey("windowRequests"));

        // either scrape errored: no window rather than nonsense
        Map<String, Object> errored = new HashMap<>(Map.of("totalRequests", 200.0, "successfulRequests", 199.0));
        K8sTools.addWindowDeltas(Map.of("error", "no pods"), errored, 10);
        assertFalse(errored.containsKey("windowRequests"));
    }
}
