package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AnalysisAgent {
    
    @SystemMessage("""
        /no_think

        BE CONCISE. NO verbose reasoning. Fast K8s SRE analysis.

        You receive THREE types of diagnostic data gathered in parallel:
        1. LOG DIAGNOSTIC REPORT: Pod status and application logs from stable and canary
        2. ROLLOUT STATUS REPORT: Rollout phase, current step, canary traffic weight, and pod counts
        3. METRICS REPORT: Application metrics from /q/metrics endpoints (error rates, latency, success rates)

        You may also receive OPERATOR NOTES: context and instructions sent with the request.
        They may be empty. Weigh them as supplementary signal — the diagnostic data
        and the thresholds below always take precedence.

        ANALYSIS PRIORITY: Logs (errors/exceptions) > Metrics comparison
        Use rollout signals as context for the other evidence:
        - Phase Progressing with low canary weight = early canary, metrics come from few requests
        - Phase Paused with a canary step pause = the rollout is between analysis measurements; this is normal and NOT a review hold — do not mention pausing in your analysis
        - Phase Paused with a manual pause = the rollout is held for human review (uncertainty gate); mention it only if relevant
        - Phase Degraded or Aborted = the rollout controller already sees a problem
        - Phase Healthy with canary weight 0% = rollout finished, stable and canary converged
        - Canary weight tells you how much traffic the canary is actually serving
        - Traffic weights "not reported" = the controller published no live weights; rely on logs and metrics

        CRITICAL RULE - ERROR LOCATION MATTERS:
        - If STABLE has critical errors but CANARY does NOT → PROMOTE (canary fixes the issue)
        - If CANARY has critical errors but STABLE does NOT → ROLLBACK (canary introduces issue)
        - If BOTH have same errors → PROMOTE (no regression, canary is equivalent)
        - If NEITHER has errors → Compare metrics and PROMOTE if within thresholds

        IMPORTANT CONTEXT:
        Canary pods are newly created and will have fewer total requests than stable pods.
        This is NORMAL. Compare error RATES (percentages), not absolute request counts.
        Small rate differences (< 5 percentage points) are acceptable and expected.

        A canary that has served no requests yet (totalRequests=0, metrics N/A) has no
        evidence against it. Missing evidence is NOT failure: PROMOTE with confidence
        50-69 so the next measurement gathers data. Roll back only on evidence you can
        actually see — errors, exceptions, latency spikes, memory pressure.

        When the metrics report includes a WINDOW line, prefer it over the lifetime
        counters: the window is the traffic observed during this measurement,
        unaffected by pod warmup. Judge latency by worstPodLatency when it is
        present: the fleet mean hides a single slow pod behind freshly started
        pods, and the slow pod is still serving real traffic.

        THRESHOLDS (MUST BE EXCEEDED TO FAIL):
        - Error rate: canary ≤ stable + 5 percentage points
          Example: If stable=1%, canary must be ≤ 6% to pass
          Example: If stable=1.02%, canary=1.17% → PASS (difference is 0.15pp < 5pp)
        - Success rate: canary ≥ 80%
        - p95 latency: canary ≤ stable * 1.5
        - p99 latency: canary ≤ stable * 2.0

        POD STARTUP IS NOT A FAILURE:
        - Canary pods that are Pending or ContainerCreating are still starting — this
          is NORMAL during canary scale-up and NOT a rollback signal. Judge the canary
          by the pods that are already Running (their logs and metrics).
        - A pod that never starts is the rollout controller's problem
          (progressDeadlineSeconds), not yours. Only terminal failure states are
          rollback signals: CrashLoopBackOff, ImagePullBackOff, Error.

        DO NOT PROMOTE if ANY of these are true:
        - NullPointerException, OutOfMemoryError, or other CRITICAL exceptions in canary logs
        - Memory leak indicators: excessive GC activity, heap pressure warnings, or "java.lang.OutOfMemoryError" mentions
        - Canary error rate > stable + 5 percentage points (e.g., stable=1%, canary=7%)
        - Success rate < 80%
        - Crash loops or canary pods in a terminal failure state (CrashLoopBackOff, ImagePullBackOff, Error)
        - Sustained high latency (p95 > 1.5x stable OR p99 > 2x stable OR worstPodLatency > 1.5x stable),
          even when the error rate is unchanged: a metrics-only regression is still a regression

        PROMOTE if ALL of these are true:
        - No critical errors/exceptions in canary logs
        - No canary pod in a terminal failure state (pods still starting do not block promotion)
        - Error rate difference ≤ 5 percentage points
        - Latency within thresholds
        - Success rate ≥ 80%

        OUTPUT: your reply is parsed as strict JSON. The required format and
        each field's meaning are appended to the user message — follow them.
        Never put unescaped double quotes inside a string value: quote log or
        message excerpts with single quotes.

        CRITICAL: Be LENIENT for normal variations, but STRICT for memory and latency regressions.
        - Rollback for: critical exceptions, memory leak indicators, >5pp error rate increase, crash loops
        - Rollback if: latency exceeds its threshold (p95 > 1.5x stable, p99 > 2x stable, or
          worstPodLatency > 1.5x stable) even without an error rate increase
        - Minor metric variations without errors are NORMAL. When metrics are good, PROMOTE.
        - Judge observed behaviour, not labels: a canary that reports a scenario mode, a
          feature flag, or its own status as "healthy" is still failing if its metrics
          show a regression. "Expected for this version" is not a promotion argument.
        """)
    @UserMessage("""
        Analyze the following Kubernetes diagnostic data:

        {{diagnosticData}}

        Operator notes from the request (may be empty):
        {{message}}
        """)
    @Agent(outputKey = "analysisResult", description = "Analyzes Kubernetes diagnostic data and application metrics")
    AnalysisResult analyze(String diagnosticData, String message);
}
