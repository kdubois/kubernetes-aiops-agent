package dev.kevindubois.rollout.agent.analysis;

import java.util.List;

public record AnalysisResult(
    boolean promote,
    int confidence,
    String analysis,
    String rootCause,
    String remediation,
    String summary,
    IssueCategory issueCategory,
    List<String> suspectClasses,
    List<MetricComparison> metricComparisons
) {

    /**
     * A single canary-vs-stable metric row for the dashboard: the metric name,
     * both observed values as short strings with units, and whether the canary
     * passed the threshold for that metric.
     */
    public record MetricComparison(String name, String canary, String stable, String verdict) {
    }

    public AnalysisResult {
        metricComparisons = metricComparisons == null ? List.of() : List.copyOf(metricComparisons);
    }

    /** Convenience for results without metric rows (tests, second-opinion paths). */
    public AnalysisResult(boolean promote, int confidence, String analysis, String rootCause,
                          String remediation, String summary, IssueCategory issueCategory,
                          List<String> suspectClasses) {
        this(promote, confidence, analysis, rootCause, remediation, summary, issueCategory,
                suspectClasses, List.of());
    }
}
