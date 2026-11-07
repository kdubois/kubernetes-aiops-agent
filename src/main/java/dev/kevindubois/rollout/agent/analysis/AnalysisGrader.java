package dev.kevindubois.rollout.agent.analysis;

import dev.kevindubois.rollout.agent.utils.TextUtils;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Independent second opinion on a completed analysis. Asks the configured decision
 * model two yes/no questions — does any evidence contradict the conclusion,
 * and is there enough data to verify it — and maps the answers onto the
 * supported-probability scale the pause gate consumes. The mapping keeps the
 * gate's three bands apart: a contradicted conclusion stays below the 0.30
 * veto bar (escalation, confidence drop), evidence too thin to verify lands
 * in the neutral band (the self-reported confidence stands — the next
 * measurement is the remedy, not a human), and a verified conclusion reaches
 * 0.5 and above.
 *
 * <p>The grader state carries the decision thresholds the analysis judged
 * against and what promote/rollback actually do, so "supported" means
 * checkable against the stated policy rather than statistically proven —
 * without them, a correct conclusion built on an early-canary sample read
 * as disputed and the gate held healthy rollouts.
 *
 * <p>Deliberately a single decision call with a threshold (grade → combine →
 * gate), shaped like the Confidence Cascade pattern langchain4j-agentic
 * provides since 1.23.0 (ConfidenceCascadePlanner). Our gate escalates to a
 * human pause instead of a second opinion tier, so the cascade planner itself
 * does not fit here — but keeping this shape means swapping to a tiered-model
 * cascade later is a wiring change, not a redesign.
 *
 * <p>The grader must never fail the measurement: without a configured
 * TYPESAFE_API_KEY, on a call failure, or when the model returns no answer it
 * reports empty and the caller falls back to the self-reported confidence.
 */
@ApplicationScoped
public class AnalysisGrader {

    private static final String CONTRADICTION_ID = "conclusion_contradicted";
    private static final String CONTRADICTION_QUESTION =
            "Does any evidence in the diagnostic data contradict the analysis conclusion — "
            + "metrics breaching the thresholds above, or canary-only errors (exceptions, OOM, "
            + "heap or GC warnings) that the conclusion ignores or calls healthy? "
            + "Thin or insufficient data is NOT a contradiction.";

    private static final String SUFFICIENCY_ID = "evidence_sufficient";

    /** P(contradicted) at or above this bar means the conclusion is disputed. */
    private static final double CONTRADICTED_BAR = 0.5;

    /** P(sufficient) below this bar means the evidence is too thin to verify. */
    private static final double SUFFICIENT_BAR = 0.5;

    /** Sample floor used when the analysis sample config is absent or disabled. */
    static final int DEFAULT_SAMPLE_FLOOR = 500;

    /**
     * Budgets inside the grader state. The metrics and rollout reports are the
     * quantitative basis of every conclusion and are small, so they are kept
     * whole; the log diagnostics are an excerpt; the conclusion always
     * survives at the end.
     */
    private static final int MAX_METRICS_LENGTH = 2000;
    private static final int MAX_ROLLOUT_LENGTH = 1000;
    private static final int MAX_DIAGNOSTIC_LENGTH = 3000;
    private static final int MAX_SUMMARY_LENGTH = 200;
    private static final int MAX_ROOT_CAUSE_LENGTH = 500;

    private final DecisionModel decisionModel;
    private final boolean enabled;
    private final int minCanaryRequests;

    @Inject
    AnalysisGrader(@ModelName("decisionmodel") DecisionModel decisionModel, Config config) {
        this(decisionModel, keyConfigured(config), sampleFloor(config));
    }

    AnalysisGrader(DecisionModel decisionModel, boolean enabled) {
        this(decisionModel, enabled, DEFAULT_SAMPLE_FLOOR);
    }

    AnalysisGrader(DecisionModel decisionModel, boolean enabled, int minCanaryRequests) {
        this.decisionModel = decisionModel;
        this.enabled = enabled;
        this.minCanaryRequests = minCanaryRequests > 0 ? minCanaryRequests : DEFAULT_SAMPLE_FLOOR;
        if (!enabled) {
            Log.info("Analysis grader disabled (no TYPESAFE_API_KEY): "
                    + "the uncertainty gate uses the self-reported confidence");
        }
    }

    private static int sampleFloor(Config config) {
        return config.getOptionalValue("analysis.sample.min-requests", Integer.class)
                .filter(value -> value > 0)
                .orElse(DEFAULT_SAMPLE_FLOOR);
    }

    private static boolean keyConfigured(Config config) {
        String apiKey = config.getOptionalValue("quarkus.langchain4j.typesafe.decisionmodel.api-key", String.class).orElse("");
        // "missing" is the sentinel that lets the decision model bean initialize without a key
        return !apiKey.isBlank() && !"missing".equals(apiKey);
    }

    /**
     * Grades a completed analysis against the evidence it was based on.
     *
     * @param result           the analysis conclusion to grade
     * @param diagnosticReport pod and log diagnostics from the workflow
     * @param rolloutReport    rollout phase, step, and traffic weights
     * @param metricsReport    application metrics (error rates, latency)
     * @return the yes-probability in [0, 1] that the conclusion is supported
     *         by the evidence, or empty when the grader is disabled or failed
     */
    public Optional<Double> grade(AnalysisResult result, String diagnosticReport, String rolloutReport,
            String metricsReport) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String graderState = state(result, diagnosticReport, rolloutReport, metricsReport);
            Log.infof("Grader state:%n%s", graderState);
            DecisionResponse response = decisionModel.decide(DecisionRequest.builder()
                    .input(graderState)
                    .question(CONTRADICTION_ID, YesNoQuestion.from(CONTRADICTION_QUESTION))
                    .question(SUFFICIENCY_ID, YesNoQuestion.from(sufficiencyQuestion()))
                    .build());
            YesNoAnswer contradiction = response.yesNo(CONTRADICTION_ID);
            YesNoAnswer sufficiency = response.yesNo(SUFFICIENCY_ID);
            if (contradiction == null || sufficiency == null) {
                Log.warn("Analysis grader returned no answer; falling back to the self-reported confidence");
                return Optional.empty();
            }
            double supported = supportedProbability(contradiction.probability(), sufficiency.probability());
            Log.infof("Grader: contradicted=%.2f sufficient=%.2f -> supported=%.2f",
                    contradiction.probability(), sufficiency.probability(), supported);
            return Optional.of(supported);
        } catch (RuntimeException e) {
            Log.warnf("Analysis grader call failed (%s); falling back to the self-reported confidence", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Maps the two answers onto the single supported-probability scale the
     * gate consumes, preserving its bands: contradicted stays below the 0.30
     * veto bar, insufficient lands in the 0.30–0.50 neutral band, and
     * supported reaches 0.5 and above.
     */
    private static double supportedProbability(double contradiction, double sufficiency) {
        if (contradiction >= CONTRADICTED_BAR) {
            return 0.30 * (1 - contradiction);
        }
        if (sufficiency < SUFFICIENT_BAR) {
            return 0.30 + 0.20 * sufficiency;
        }
        return 0.50 + 0.50 * sufficiency * (1 - contradiction);
    }

    /**
     * The sufficiency question names the sample floor so the grader judges the
     * canary against a fixed threshold instead of comparing its lifetime count
     * with a stable pod that has served traffic for days.
     */
    private String sufficiencyQuestion() {
        return "Is the canary sample large enough to verify the conclusion — at least "
                + minCanaryRequests + " requests in the measurement window (or the lifetime "
                + "total when no window line is present), or a clear canary-only exception "
                + "in the logs? A canary lifetime total smaller than stable's is normal "
                + "because stable ran longer, and does not by itself make the evidence thin.";
    }

    /**
     * Condensed grader state: the decision context first — the thresholds the
     * analysis judged against and what promote/rollback actually do, so
     * "supported" is checkable against the stated policy — then the metrics
     * and rollout reports (they are the quantitative basis of every
     * conclusion and are small enough to keep whole), then the error, warning
     * and resource-pressure lines from the log diagnostics, then the
     * conclusion. The log excerpt is filtered by signal, not position: the
     * report lists stable pods before canary pods, so a positional excerpt
     * kept the benign stable half and cut the canary evidence the conclusion
     * rests on.
     */
    private String state(AnalysisResult result, String diagnosticReport, String rolloutReport,
            String metricsReport) {
        return "Decision context:\n"
                + "- thresholds the analysis applies: error rate canary <= stable + 5 percentage points; "
                + "success rate canary >= 80%; p95 latency canary <= 1.5x stable; p99 latency canary <= 2.0x stable\n"
                + "- action semantics: PROMOTE continues the rollout to its next measurement "
                + "(more canary traffic, ~30s later); ROLLBACK aborts to the stable version immediately\n"
                + "- evidence sufficiency: the canary sample is sufficient at " + minCanaryRequests
                + "+ requests in the measurement window (or the lifetime total when no window is "
                + "present). Stable served more requests because it ran longer; compare rates, not "
                + "totals. A repeated canary-only exception verifies a rollback conclusion even "
                + "when its rate is low, and contradicts a conclusion that calls the canary healthy\n\n"
                + "Metrics:\n"
                + TextUtils.truncate(metricsReport, MAX_METRICS_LENGTH)
                + "\n\nRollout status:\n"
                + TextUtils.truncate(rolloutReport, MAX_ROLLOUT_LENGTH)
                + "\n\nLog diagnostics:\n"
                + diagnosticsExcerpt(diagnosticReport)
                + "\n\nAnalysis conclusion:\n"
                + "- decision: " + (result.promote() ? "PROMOTE" : "ROLLBACK") + "\n"
                + "- confidence: " + result.confidence() + "/100\n"
                + "- summary: " + TextUtils.truncate(result.summary(), MAX_SUMMARY_LENGTH) + "\n"
                + "- root cause: " + TextUtils.truncate(result.rootCause(), MAX_ROOT_CAUSE_LENGTH);
    }

    /** Lines worth grading: errors, warnings, and resource-pressure signals. */
    private static final Pattern SIGNAL_LINE =
            Pattern.compile("(?i)error|warn|exception|outofmemory|heap|gc |crash|fail|leak|killed");

    /** JVM startup flags match the signal words but carry no log signal. */
    private static final Pattern NOISE_LINE =
            Pattern.compile("-XX:|INFO exec");

    /** Section headers of the diagnostics report: which side a log line came from. */
    private static final Pattern SECTION_HEADER =
            Pattern.compile("(?i)^(STABLE|CANARY) (POD|LOGS|METRICS)");

    /**
     * Signal-focused excerpt of the log diagnostics: the error, warning and
     * resource-pressure lines with the stable/canary section headers carried
     * along — error location decides the verdict (errors in stable justify a
     * promote, the same errors in canary justify a rollback), so signal lines
     * without their section are ambiguous. Repeated patterns are collapsed to
     * one line with an occurrence count: a demo app logging a benign warning
     * on every request produces dozens of near-identical lines, and the flood
     * reads as an incident while the distinct patterns are the actual signal.
     * JVM startup flags are excluded — they mention heaps, GC and OOM without
     * being log output.
     */
    private String diagnosticsExcerpt(String diagnosticReport) {
        if (diagnosticReport == null || diagnosticReport.isBlank()) {
            return "(no log diagnostics)";
        }
        String[] lines = diagnosticReport.split("\n");
        LinkedHashMap<String, LinkedHashMap<String, Long>> bySection = new LinkedHashMap<>();
        long matched = 0;
        String section = "";
        for (String line : lines) {
            if (SECTION_HEADER.matcher(line).find()) {
                section = line.stripTrailing();
                bySection.putIfAbsent(section, new LinkedHashMap<>());
            }
            if (SIGNAL_LINE.matcher(line).find() && !NOISE_LINE.matcher(line).find()) {
                matched++;
                bySection.computeIfAbsent(section, k -> new LinkedHashMap<>())
                        .merge(line.stripTrailing().replaceAll("\\d+", "#"), 1L, Long::sum);
            }
        }
        if (bySection.isEmpty()) {
            return "no sectioned log diagnostics in " + lines.length + " lines";
        }
        long distinct = bySection.values().stream().mapToLong(Map::size).sum();
        StringBuilder excerpt = new StringBuilder();
        for (var sec : bySection.entrySet()) {
            if (excerpt.length() >= MAX_DIAGNOSTIC_LENGTH) {
                break;
            }
            excerpt.append(sec.getKey()).append('\n');
            if (sec.getValue().isEmpty()) {
                excerpt.append("  (no error, warning or resource-pressure lines)\n");
            } else {
                for (var pattern : sec.getValue().entrySet()) {
                    excerpt.append("  ").append(pattern.getValue()).append("x ").append(pattern.getKey()).append('\n');
                }
            }
        }
        return distinct + " distinct error/warning patterns ("
                + matched + " matching lines of " + lines.length + "):\n" + excerpt;
    }
}
