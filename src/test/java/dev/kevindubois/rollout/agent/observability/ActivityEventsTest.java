package dev.kevindubois.rollout.agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kevindubois.rollout.agent.analysis.AnalysisResult;
import dev.kevindubois.rollout.agent.analysis.IssueCategory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the activity events the analysis conclusion publishes: the metric
 * rows as a JSON event the dashboard renders as a table, and the insight.
 */
@QuarkusTest
class ActivityEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ActivityEvents activityEvents;

    @Inject
    ActivityEventStore store;

    @BeforeEach
    void clear() {
        store.clear();
    }

    @Test
    void metricRowsArePublishedAsJsonBeforeTheInsight() throws Exception {
        var result = new AnalysisResult(true, 95, "Canary is healthy — promote.", "No issues",
                "Promote", "Canary healthy - promote", IssueCategory.NO_ISSUES, List.of(),
                List.of(
                        new AnalysisResult.MetricComparison("errorRate", "0.9%", "1.0%", "pass"),
                        new AnalysisResult.MetricComparison("p95", "48ms", "47ms", "pass")));

        activityEvents.analysisSummary(result, "glm-5-3");

        var metrics = store.getEvents().stream()
                .filter(e -> "ANALYSIS_METRICS".equals(e.type()))
                .findFirst().orElseThrow();
        var insight = store.getEvents().stream()
                .filter(e -> "ANALYSIS_INSIGHT".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(metrics.id()).isLessThan(insight.id());

        JsonNode rows = MAPPER.readTree(metrics.details());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("name").asText()).isEqualTo("errorRate");
        assertThat(rows.get(0).path("canary").asText()).isEqualTo("0.9%");
        assertThat(rows.get(0).path("stable").asText()).isEqualTo("1.0%");
        assertThat(rows.get(0).path("verdict").asText()).isEqualTo("pass");
    }

    @Test
    void resultWithoutRowsPublishesNoMetricsEvent() {
        var result = new AnalysisResult(true, 95, "Canary is healthy — promote.", "No issues",
                "Promote", "Canary healthy - promote", IssueCategory.NO_ISSUES, List.of());

        activityEvents.analysisSummary(result, "glm-5-3");

        assertThat(store.getEvents())
                .noneMatch(e -> "ANALYSIS_METRICS".equals(e.type()));
    }
}
