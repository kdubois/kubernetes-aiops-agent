package dev.kevindubois.rollout.agent.k8s;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the multi-pod metrics aggregation: counters sum, rates are recomputed from
 * the sums, and latency quantiles take the worst pod.
 */
class MetricsAggregationTest {

    private static Map<String, Object> podMetrics(double total, double successful,
            double p95, double p99, Double heapUsed, Double gcCount) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalRequests", total);
        metrics.put("successfulRequests", successful);
        metrics.put("latencyP95Ms", p95);
        metrics.put("latencyP99Ms", p99);
        metrics.put("latencySumSeconds", p95 * total / 1000);
        metrics.put("latencyCount", total);
        if (heapUsed != null) {
            metrics.put("heapUsedMb", heapUsed);
            metrics.put("heapMaxMb", 512.0);
        }
        if (gcCount != null) {
            metrics.put("gcCount", gcCount);
        }
        return metrics;
    }

    @Test
    void countersSumAndRatesRecompute() {
        Map<String, Object> aggregated = K8sTools.aggregateMetrics(List.of(
                podMetrics(100, 99, 40.0, 90.0, null, null),
                podMetrics(300, 294, 42.0, 95.0, null, null)));

        assertEquals(400.0, (double) aggregated.get("totalRequests"), 0.001);
        assertEquals(393.0, (double) aggregated.get("successfulRequests"), 0.001);
        // Weighted rate across both pods, not an average of per-pod rates
        assertEquals(98.25, (double) aggregated.get("calculatedSuccessRate"), 0.001);
        assertEquals(1.75, (double) aggregated.get("errorRate"), 0.001);
        assertEquals(2L, aggregated.get("podCount"));
    }

    @Test
    void latencyTakesTheWorstPod() {
        Map<String, Object> aggregated = K8sTools.aggregateMetrics(List.of(
                podMetrics(500, 500, 48.0, 120.0, null, null),
                podMetrics(50, 49, 250.0, 300.0, null, null)));

        assertEquals(250.0, (double) aggregated.get("latencyP95Ms"), 0.001);
        assertEquals(300.0, (double) aggregated.get("latencyP99Ms"), 0.001);
    }

    @Test
    void heapAndGcTakeTheWorstPod() {
        Map<String, Object> aggregated = K8sTools.aggregateMetrics(List.of(
                podMetrics(100, 100, 40.0, 90.0, 200.0, 15.0),
                podMetrics(100, 100, 40.0, 90.0, 480.0, 250.0)));

        assertEquals(480.0, (double) aggregated.get("heapUsedMb"), 0.001);
        assertEquals(250.0, (double) aggregated.get("gcCount"), 0.001);
    }

    @Test
    void unscrapeablePodsAreSkipped() {
        Map<String, Object> aggregated = K8sTools.aggregateMetrics(java.util.Arrays.asList(
                podMetrics(100, 99, 45.0, 100.0, null, null),
                Map.of("error", "Failed to connect to pod metrics endpoint"),
                null));

        assertFalse(aggregated.containsKey("error"));
        assertEquals(100.0, (double) aggregated.get("totalRequests"), 0.001);
        assertEquals(1L, aggregated.get("podCount"));
    }

    @Test
    void allPodsFailedToScrape() {
        Map<String, Object> aggregated = K8sTools.aggregateMetrics(List.of(
                Map.of("error", "Pod IP not available"),
                Map.of("error", "Failed to connect")));

        assertTrue(aggregated.containsKey("error"));
    }

    @Test
    void emptyPodList() {
        Map<String, Object> aggregated = K8sTools.aggregateMetrics(List.of());

        assertTrue(aggregated.containsKey("error"));
        assertEquals("No pods found", aggregated.get("error"));
    }

    @Test
    void versionLabelsComeThrough() {
        Map<String, Object> first = podMetrics(100, 100, 45.0, 100.0, null, null);
        first.put("version", "4.0.0");
        first.put("scenario", "latency-drift");

        Map<String, Object> aggregated = K8sTools.aggregateMetrics(List.of(
                first, podMetrics(100, 100, 45.0, 100.0, null, null)));

        assertEquals("4.0.0", aggregated.get("version"));
        assertEquals("latency-drift", aggregated.get("scenario"));
    }
}
