package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

public record AnalysisResult(
    @Description("true to continue the rollout, false to roll back the canary")
    boolean promote,

    @Description("confidence in the conclusion: 90-100 clear, 70-89 good, 50-69 mixed, below 50 unclear")
    int confidence,

    @Description("evidence summary as 2-4 short lines separated by \\n — line 1: the verdict and why in one sentence; line 2: key metric comparisons, canary vs stable; line 3+: log findings")
    String analysis,

    @Description("the issue found, or 'No issues'")
    String rootCause,

    @Description("the action to take, or 'Promote'")
    String remediation,

    @Description("one-line summary with the key metric, max 120 chars")
    String summary,

    @Description("CODE_BUG: NPE, logic error, unhandled exception, code-level defect. OPERATIONAL: OOM, memory leak, CPU throttling, resource exhaustion, GC storms. NO_ISSUES: canary is healthy")
    IssueCategory issueCategory,

    @Description("Java class names (simple, not FQCN) appearing in stack traces or error logs — application classes only, not framework classes; empty if no suspects")
    List<String> suspectClasses,

    @Description("one row per metric the report actually shows (errorRate, successRate, p95, p99, fleetMeanLatency, worstPodLatency, heap/GC when reported) — at most 6 rows; include fail rows, they show what broke; empty when the report has no numbers")
    List<MetricComparison> metricComparisons
) {

    /**
     * A single canary-vs-stable metric row for the dashboard: the metric name,
     * both observed values as short strings with units, and whether the canary
     * passed the threshold for that metric.
     */
    public record MetricComparison(
        @Description("the metric name, e.g. errorRate or p95")
        String name,

        @Description("the canary's observed value, short string with units, e.g. '0.94%' or '47.7ms'")
        String canary,

        @Description("the stable fleet's observed value, short string with units")
        String stable,

        @Description("pass or fail, judged against the thresholds")
        String verdict
    ) {
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
