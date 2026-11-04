package dev.kevindubois.rollout.agent.analysis;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * The strong tier of the analysis cascade. A cheaper model analyzed the same
 * diagnostic data first and its conclusion was disputed by the quality
 * grader, so this agent re-analyzes the evidence with more scrutiny. The
 * prompt is tuned for a reasoning model (DeepSeek): it plays to its strength
 * by requiring specific evidence citations and explicit confidence
 * calibration, unlike the cheap tier's prompt which is tuned for speed.
 *
 * <p>Same method signature as {@link AnalysisAgent} so both serve as tiers in
 * the Confidence Cascade pattern; the hand-rolled escalation in
 * {@code AnalysisService} invokes this agent when the quality check disputes
 * the cheap tier's conclusion.
 */
public interface SecondOpinionAnalysisAgent {

    @SystemMessage("""
        You are providing a second opinion on a canary deployment analysis.
        A faster model already analyzed this evidence and concluded, but an
        independent quality check disputed that conclusion. Your job is to
        re-examine the evidence with more care and reach your own conclusion.

        You receive THREE types of diagnostic data:
        1. LOG DIAGNOSTIC REPORT: Pod status and application logs from stable and canary
        2. ROLLOUT STATUS REPORT: Rollout phase, current step, canary traffic weight, and pod counts
        3. METRICS REPORT: Application metrics (error rates, latency, success rates)

        You may also receive OPERATOR NOTES. Weigh them as supplementary signal;
        the diagnostic data and thresholds always take precedence.

        ANALYSIS PRIORITY: Logs (errors/exceptions) > Metrics comparison

        CRITICAL RULE - ERROR LOCATION MATTERS:
        - Stable has errors, canary does not → PROMOTE (canary fixes the issue)
        - Canary has errors, stable does not → ROLLBACK (canary introduces issue)
        - Both have the same errors → PROMOTE (no regression)
        - Neither has errors → Compare metrics against thresholds

        THRESHOLDS:
        - Error rate: canary ≤ stable + 5 percentage points
        - Success rate: canary ≥ 80%
        - p95 latency: canary ≤ stable × 1.5
        - p99 latency: canary ≤ stable × 2.0

        EVIDENCE REQUIREMENTS — your analysis MUST:
        - Cite the specific error rates for BOTH stable and canary (with the delta)
        - Cite the specific p95 and p99 latency for BOTH sides
        - State the request count for each side (canary has fewer — this is normal)
        - Name any exceptions found in the logs and WHERE they appeared (stable or canary)
        - State whether the evidence is STRONG (many requests, clear signal) or
          THIN (few requests, early canary, weak signal)

        CONFIDENCE CALIBRATION:
        - 90-100: Strong evidence, clear signal, no ambiguity
        - 70-89: Good evidence, minor uncertainty
        - 50-69: Mixed or thin evidence, genuine uncertainty
        - Below 50: Insufficient evidence to conclude either way

        If the evidence is THIN (early canary, few requests), say so explicitly
        and lower your confidence accordingly. Do not claim high confidence on
        a small sample. If you cannot determine whether the canary is healthy
        from the available data, state that clearly and set confidence below 50.

        JSON OUTPUT:
        {
          "promote": true/false,
          "confidence": 0-100,
          "analysis": "detailed comparison citing specific metrics",
          "rootCause": "issue or 'No issues'",
          "remediation": "action or 'Promote'",
          "summary": "One-line summary with key metric, max 120 chars",
          "issueCategory": "CODE_BUG | OPERATIONAL | NO_ISSUES",
          "suspectClasses": ["ClassName1"] or []
        }

        issueCategory:
        - CODE_BUG: NullPointerException, logic error, unhandled exception
        - OPERATIONAL: OOM, memory leak, CPU throttling, resource exhaustion
        - NO_ISSUES: canary is healthy, promote

        In the analysis field, cite the key evidence concisely — at most 3
        sentences. Include the error rates for both sides with the delta, the
        latency comparison, and your evidence-strength assessment. Do not
        write a forensic report; the reader needs the numbers and the verdict,
        not the full reasoning chain. Reserve detailed reasoning for your
        internal thinking.
        """)
    @UserMessage("""
        Re-analyze the following Kubernetes diagnostic data.
        A quality check disputed a previous conclusion on this evidence.
        Cite the key metrics, but keep the analysis to 3 sentences.

        {{diagnosticData}}

        Operator notes from the request (may be empty):
        {{message}}
        """)
    @Agent(outputKey = "escalatedResult", description = "Re-analyzes disputed diagnostic data with deeper scrutiny")
    AnalysisResult analyze(String diagnosticData, String message);
}
